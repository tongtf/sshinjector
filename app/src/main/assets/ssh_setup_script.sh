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
#
# BACKENDS (auto-detected):
#   * openssh  - full hardening: dedicated account + chroot + Match block +
#                pubkey-only auth + immutable authorized_keys. Also works on
#                BusyBox-based systems (OpenWrt with openssh-server): falls
#                back to `adduser`, reloads via /etc/init.d/sshd.
#   * dropbear - basic compatibility (OpenWrt default): creates the account,
#                locks the password, installs the pubkey. Chroot/Match are
#                not supported by dropbear, so no per-user isolation is done;
#                the global dropbear config (/etc/config/dropbear) is NOT
#                modified. A marker file records that provisioning ran.
set -euo pipefail

# Fixed provisioning account (hardcoded, never parameterized).
ACCT=sshproxy
ACCT_HOME=/home/${ACCT}
SSHD_CONFIG=/etc/ssh/sshd_config
SSHD_CONFIG_BAK=${SSHD_CONFIG}.sshinjector.bak
DROPBEAR_DIR=/etc/dropbear
DROPBEAR_AUTHKEYS=${DROPBEAR_DIR}/authorized_keys
DROPBEAR_MARKER=${DROPBEAR_DIR}/sshinjector.configured

# Login shell for the tunnel account (BusyBox/OpenWrt has no /usr/sbin/nologin).
NOLOGIN=$(command -v nologin 2>/dev/null || echo /bin/false)

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
# 0. Backend detection
# ---------------------------------------------------------------------------
if command -v sshd >/dev/null 2>&1; then
    BACKEND=openssh
elif command -v dropbear >/dev/null 2>&1; then
    BACKEND=dropbear
else
    echo "ERROR: neither sshd (OpenSSH) nor dropbear found on this system" >&2
    echo "On OpenWrt: opkg install openssh-server, or use the default dropbear" >&2
    exit 2
fi
echo "SSHInjector backend detected: ${BACKEND}"

# ---------------------------------------------------------------------------
# Account creation (idempotent, BusyBox-compatible)
# ---------------------------------------------------------------------------
ensure_account() {
    if ! id "${ACCT}" >/dev/null 2>&1; then
        if command -v useradd >/dev/null 2>&1; then
            useradd -M -s "${NOLOGIN}" -d "${ACCT_HOME}" -p '!' "${ACCT}"
        else
            adduser -H -D -s "${NOLOGIN}" -h "${ACCT_HOME}" "${ACCT}"
        fi
    fi
    # Lock password regardless (defense in depth for PasswordAuthentication no).
    passwd -l "${ACCT}" >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# OpenSSH backend: full chroot + Match hardening
# ---------------------------------------------------------------------------
configure_openssh() {
    CHROOT=${ACCT_HOME}/chroot
    ensure_account

    # chroot skeleton (ChrootDirectory must be root-owned per OpenSSH)
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

    # Runtime libraries for dynamic linking + NSS resolution
    SSHD_BIN=$(command -v sshd 2>/dev/null || true)
    if [ -n "${SSHD_BIN}" ]; then
        ldd "${SSHD_BIN}" 2>/dev/null | awk '/=>/ {print $3}' | grep '^/' \
            | while read -r lib; do
                mkdir -p "${CHROOT}$(dirname "${lib}")"
                cp -n "${lib}" "${CHROOT}${lib}" 2>/dev/null || true
            done
    fi

    # Static fallback list for common libc/NSS libs across distros (incl. musl).
    for lib in libc.so.6 libnss_dns.so.2 libnss_files.so.2 libresolv.so.2 \
               ld-linux.so.2 ld-linux-x86-64.so.2 ld-linux-aarch64.so.1 \
               ld-musl-i386.so.1 ld-musl-x86_64.so.1 ld-musl-aarch64.so.1; do
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

    # Install public key (immutable-lock to prevent tampering)
    AUTHKEYS=${ACCT_HOME}/.ssh/authorized_keys
    chattr -i "${AUTHKEYS}" 2>/dev/null || true
    touch "${AUTHKEYS}"
    grep -qFf "${PUBKEY_FILE}" "${AUTHKEYS}" 2>/dev/null || cat "${PUBKEY_FILE}" >> "${AUTHKEYS}"
    chown "${ACCT}:${ACCT}" "${AUTHKEYS}"
    chmod 600 "${AUTHKEYS}"
    chattr +i "${AUTHKEYS}" 2>/dev/null || true

    # sshd_config Match block (idempotent, hardcoded content)
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

    # Gate reload on config validity + correct ownership/permissions
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
        service ssh reload 2>/dev/null || service sshd reload 2>/dev/null || true
    else
        /etc/init.d/ssh reload 2>/dev/null || /etc/init.d/sshd restart 2>/dev/null || true
    fi
}

# ---------------------------------------------------------------------------
# dropbear backend (OpenWrt default): basic compatibility mode
# ---------------------------------------------------------------------------
configure_dropbear() {
    ensure_account

    # dropbear global authorized_keys + account-level fallback (dropbear reads
    # both depending on its AUTHORIZED_KEYS setting).
    mkdir -p "${DROPBEAR_DIR}" "${ACCT_HOME}/.ssh"
    chown root:root "${DROPBEAR_DIR}"
    chmod 700 "${DROPBEAR_DIR}"
    chown -R "${ACCT}:${ACCT}" "${ACCT_HOME}/.ssh"
    chmod 700 "${ACCT_HOME}/.ssh"

    touch "${DROPBEAR_AUTHKEYS}"
    grep -qFf "${PUBKEY_FILE}" "${DROPBEAR_AUTHKEYS}" 2>/dev/null ||
        cat "${PUBKEY_FILE}" >> "${DROPBEAR_AUTHKEYS}"
    chown root:root "${DROPBEAR_AUTHKEYS}"
    chmod 600 "${DROPBEAR_AUTHKEYS}"

    AUTHKEYS=${ACCT_HOME}/.ssh/authorized_keys
    touch "${AUTHKEYS}"
    grep -qFf "${PUBKEY_FILE}" "${AUTHKEYS}" 2>/dev/null || cat "${PUBKEY_FILE}" >> "${AUTHKEYS}"
    chown "${ACCT}:${ACCT}" "${AUTHKEYS}"
    chmod 600 "${AUTHKEYS}"

    # Marker consumed by the app's verify step (no Match block on dropbear).
    echo "configured" > "${DROPBEAR_MARKER}"
    chmod 600 "${DROPBEAR_MARKER}"

    # dropbear re-reads authorized_keys per connection; no reload needed.
    echo "NOTE: dropbear backend - chroot/Match hardening not available; " \
        "global dropbear config was NOT modified"
}

case "${BACKEND}" in
    openssh) configure_openssh ;;
    dropbear) configure_dropbear ;;
esac

echo "SSHInjector provisioning complete: account=${ACCT} backend=${BACKEND}"
