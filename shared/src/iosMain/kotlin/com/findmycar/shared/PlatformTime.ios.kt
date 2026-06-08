package com.findmycar.shared

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun platformTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
