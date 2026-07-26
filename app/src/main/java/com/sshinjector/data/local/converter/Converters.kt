package com.sshinjector.data.local.converter

import androidx.room.TypeConverter
import com.sshinjector.data.local.entity.DnsMode
import java.util.Date

class Converters {
    @TypeConverter
    fun fromDate(value: Date?): Long? = value?.time

    @TypeConverter
    fun toDate(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun fromDnsMode(mode: DnsMode?): String? = mode?.name

    @TypeConverter
    fun toDnsMode(name: String?): DnsMode? = name?.let { DnsMode.valueOf(it) }
}