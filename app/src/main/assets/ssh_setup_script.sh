#!/bin/sh
# SSHInjector server-side provisioning script.
#
# SECURITY MODEL:
#   * All parameters are FIXED and hardcoded. No shell arguments are accepted.
#   * The public key is read from a temp file written via SSH stdin
#     (PUBKEY_FILE path is passed as the single, strictly-validated arg).
#   * The script is verified on the server (sha256sum) before execution.
#   * Every step is idempotent: safe to re-run on an existing setup.
#   * sshd is only reloaded after `sshd -t` passes; config is backed up and
#     restored on failure.
set -euo pipefail

# Fixed provisioning account (hardcoded, never parameterized).
ACCT=sshproxy
ACCT_HOME=/home/${ACCT}
CHROOT=${ACCT_HOME}/chroot
SSHD_CONFIG=/etc/ssh/sshd_config
SSHD_CONFIG_BAK=${SSHD_CONFIG}.sshinjector.bak

# Public key file: written by the app via SSH stdin, single validated argument.
if [ $# -ne 1 ] || [ -z "$1" ]; then
    echo "usage: ssh_setup_script.sh <pubkey_file>" >&2
    exit 2
fi
PUBKEY_FILE=$1

if [ ! -r "${PUBKEY_FILE}" ] || [ ! -s "${PUBKEY_FILE}" ]; then
    echo "ERROR: pubkey file missing or empty" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 1. Account (idempotent: create only if missing)
# ---------------------------------------------------------------------------
if ! id "${ACCT}" >/dev/null 2>&1; then
    useradd -M -s /usr/sbin/nologin -d "${ACCT_HOME}" -p '!' "${ACCT}"
fi

# Lock password regardless (defense in depth for PasswordAuthentication no).
passwd -l "${ACCT}" >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# 2. chroot skeleton (ChrootDirectory must be root-owned per OpenSSH)
# ---------------------------------------------------------------------------
mkdir -p "${CHROOT}/dev" "${CHROOT}/etc" "${CHROOT}/lib" "${CHROOT}/lib64"
mkdir -p "${ACCT_HOME}/.ssh"
chown -R root:root "${CHROOT}"
chmod 755 "${CHROOT}"
chown -R "${ACCT}:${ACCT}" "${ACCT_HOME}/.ssh"
chmod 700 "${ACCT_HOME}/.ssh"

# /dev nodes (idempotent)
for dev in null zero random urandom; do
    case "${dev}" in
        null)   [ -e "${CHROOT}/dev/null" ]   || mknod -m 666 "${CHROOT}/dev/null"   c 1 3 ;;
        zero)   [ -e "${CHROOT}/dev/zero" ]   || mknod -m 666 "${CHROOT}/dev/zero"   c 1 5 ;;
        random) [ -e "${CHROOT}/dev/random" ] || mknod -m 666 "${CHROOT}/dev/random" c 1 8 ;;
        urandom)[ -e "${CHROOT}/dev/urandom" ]|| mknod -m 666 "${CHROOT}/dev/urandom" c 1 9 ;;
    esac
done

# ---------------------------------------------------------------------------
# 3. Runtime libraries for dynamic linking + NSS resolution
# ---------------------------------------------------------------------------
# Dynamic discovery of sshd dependencies (covers most distros).
SSHD_BIN=$(command -v sshd 2>/dev/null || true)
if [ -n "${SSHD_BIN}" ]; then
    ldd "${SSHD_BIN}" 2>/dev/null | awk '/=>/ {print $3}' | grep '^/' \
        | while read -r lib; do
            mkdir -p "${CHROOT}$(dirname "${lib}")"
            cp -n "${lib}" "${CHROOT}${lib}" 2>/dev/null || true
        done
fi

# Static fallback list for common libc/NSS libs across distros.
for lib in libc.so.6 libnss_dns.so.2 libnss_files.so.2 libresolv.so.2 \
           ld-linux.so.2 ld-linux-x86-64.so.2 ld-linux-aarch64.so.1; do
    find /lib /lib64 /usr/lib /usr/lib64 -name "${lib}" 2>/dev/null \
        | while read -r found; do
            mkdir -p "${CHROOT}$(dirname "${found}")"
            cp -n "${found}" "${CHROOT}${found}" 2>/dev/null || true
        done
done

# /etc files required for name resolution inside chroot.
for f in host.conf nsswitch.conf resolv.conf; do
    [ -f "/etc/${f}" ] && cp -n "/etc/${f}" "${CHROOT}/etc/${f}" 2>/dev/null || true
done

# ---------------------------------------------------------------------------
# 4. Install public key (immutable-lock to prevent tampering)
# ---------------------------------------------------------------------------
AUTHKEYS=${ACCT_HOME}/.ssh/authorized_keys
chattr -i "${AUTHKEYS}" 2>/dev/null || true
touch "${AUTHKEYS}"
grep -qFf "${PUBKEY_FILE}" "${AUTHKEYS}" 2>/dev/null || cat "${PUBKEY_FILE}" >> "${AUTHKEYS}"
chown "${ACCT}:${ACCT}" "${AUTHKEYS}"
chmod 600 "${AUTHKEYS}"
chattr +i "${AUTHKEYS}" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 5. sshd_config Match block (idempotent, hardcoded content)
# ---------------------------------------------------------------------------
if ! grep -q "Match User ${ACCT}" "${SSHD_CONFIG}" 2>/dev/null; then
    cp -a "${SSHD_CONFIG}" "${SSHD_CONFIG_BAK}" 2>/dev/null || true
    cat >> "${SSHD_CONFIG}" <<'EOF'

# --- SSHInjector tunnel account ---
Match User sshproxy
    ChrootDirectory /home/sshproxy/chroot
    X11Forwarding no
    AllowTcpForwarding yes
    PermitTTY no
    PasswordAuthentication no
    PubkeyAuthentication yes
EOF
fi

# ---------------------------------------------------------------------------
# 6. Gate reload on config validity + correct ownership/permissions
# ---------------------------------------------------------------------------
chmod 600 "${SSHD_CONFIG}"
chown root:root "${SSHD_CONFIG}"

if ! sshd -t 2>/tmp/sshinjector_sshd_test.err; then
    echo "ERROR: sshd -t failed, restoring backup" >&2
    cat /tmp/sshinjector_sshd_test.err >&2 2>/dev/null || true
    rm -f /tmp/sshinjector_sshd_test.err
    if [ -f "${SSHD_CONFIG_BAK}" ]; then
        cp -a "${SSHD_CONFIG_BAK}" "${SSHD_CONFIG}"
    fi
    exit 1
fi
rm -f /tmp/sshinjector_sshd_test.err

if command -v systemctl >/dev/null 2>&1; then
    systemctl reload sshd 2>/dev/null || systemctl reload ssh 2>/dev/null || true
elif command -v service >/dev/null 2>&1; then
    service ssh reload 2>/dev/null || true
else
    /etc/init.d/ssh reload 2>/dev/null || true
fi

echo "SSHInjector provisioning complete: account=${ACCT}"
