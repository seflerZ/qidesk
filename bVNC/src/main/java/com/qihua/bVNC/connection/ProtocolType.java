package com.qihua.bVNC.connection;

/**
 * The 5 remote-desktop protocols this client supports.
 *
 * SPICE covers both SPICE-direct and Opaque (oVirt/PVE) app flavors — both
 * use SpiceConnectionInitializer; the flavor split happens at app-flavor
 * level (Utils.isOpaque(ctx)), not at protocol-strategy level.
 */
public enum ProtocolType {
    VNC,
    RDP,
    SPICE,
    NVSTREAM,
    SSH,
}
