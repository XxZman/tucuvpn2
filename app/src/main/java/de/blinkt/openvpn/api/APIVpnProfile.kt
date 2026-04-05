package de.blinkt.openvpn.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable companion for APIVpnProfile.aidl.
 * Field order and writeToParcel order must match the ics-openvpn implementation
 * exactly, otherwise deserialization will produce garbage.
 */
class APIVpnProfile(
    val mUUID: String,
    val mName: String,
    val mUserEditable: Boolean
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(mUUID)
        parcel.writeString(mName)
        parcel.writeByte(if (mUserEditable) 1 else 0)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<APIVpnProfile> {
        override fun createFromParcel(parcel: Parcel) = APIVpnProfile(parcel)
        override fun newArray(size: Int): Array<APIVpnProfile?> = arrayOfNulls(size)
    }
}
