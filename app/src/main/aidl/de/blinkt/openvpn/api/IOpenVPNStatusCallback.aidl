// Exact mirror of ics-openvpn external API — do not modify method order.
package de.blinkt.openvpn.api;

interface IOpenVPNStatusCallback {
    /**
     * Called whenever the VPN state changes.
     * @param uuid    profile UUID
     * @param state   state string (CONNECTED, DISCONNECTED, CONNECTING, etc.)
     * @param message human-readable message
     * @param level   log level
     */
    oneway void newStatus(String uuid, String state, String message, String level);
}
