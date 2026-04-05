// Exact mirror of ics-openvpn external API — method ORDER is critical,
// AIDL uses positional transaction codes. Do not reorder or skip methods.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

interface IOpenVPNAPIService {
    int getAPIVersion();

    oneway void registerStatusCallback(IOpenVPNStatusCallback cb);
    oneway void unregisterStatusCallback(IOpenVPNStatusCallback cb);

    APIVpnProfile addNewVPNProfile(in String name, in boolean userEditable, in String config);
    List<APIVpnProfile> getProfiles();
    void removeProfile(in APIVpnProfile profile);

    void startProfile(in String profileUUID);

    /**
     * Start a VPN connection from an inline .ovpn config string.
     * This is the primary method we use.
     */
    void startVPN(in String inlineConfig);

    boolean protectSocket(in int socket);

    void disconnect();
    void pause();
    void resume();

    Intent prepareVPNService();

    String getStatus();
    String getConnectedVpnProfile();
}
