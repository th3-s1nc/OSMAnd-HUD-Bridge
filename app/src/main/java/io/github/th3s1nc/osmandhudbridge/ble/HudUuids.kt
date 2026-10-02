package io.github.th3s1nc.osmandhudbridge.ble

import java.util.UUID

object HudUuids {
    val SERVICE: UUID = UUID.fromString("93C3D190-B994-4E6C-A32E-C73D4A2ED762")
    val WRITE: UUID = UUID.fromString("93C3D191-B994-4E6C-A32E-C73D4A2ED762")   // Handle 0x21
    val NOTIFY: UUID = UUID.fromString("93C3D192-B994-4E6C-A32E-C73D4A2ED762")  // Handle 0x23, CCCD 0x24
    val NOTIFY2: UUID = UUID.fromString("93C3D194-B994-4E6C-A32E-C73D4A2ED762") // Handle 0x28, CCCD 0x29
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
