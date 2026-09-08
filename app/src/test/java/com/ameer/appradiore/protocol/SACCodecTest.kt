package com.ameer.appradiore.protocol

import com.ameer.appradiore.core.protocol.sac.SACCodec
import com.ameer.appradiore.core.protocol.sac.SACCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SACCodecTest {

    @Test
    fun testEncodeAuthBegin() {
        val encoded = SACCodec.encode(SACCommand.AuthBegin)
        assertArrayEquals(byteArrayOf(0x00), encoded)
    }

    @Test
    fun testEncodeAuthEnd() {
        val encoded = SACCodec.encode(SACCommand.AuthEnd(isSuccess = true, majorVersion = 3, minorVersion = 1))
        val expected = byteArrayOf(
            0x01,                   // subtype
            0x01,                   // success
            0x00, 0x03,             // major version 3
            0x00, 0x01              // minor version 1
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testDecodeAuthResponse() {
        val payload = byteArrayOf(
            0x00,                   // subtype: AuthResponse
            0x00,                   // result: 0 (OK)
            0x00, 0x03,             // major 3
            0x00, 0x01              // minor 1
        )
        val decoded = SACCodec.decode(SACCommand.OP_A2S_AUTH, payload)
        assertTrue(decoded is SACCommand.AuthResponse)
        val authResp = decoded as SACCommand.AuthResponse
        assertEquals(0.toByte(), authResp.result)
        assertEquals(3.toShort(), authResp.majorVersion)
        assertEquals(1.toShort(), authResp.minorVersion)
    }

    @Test
    fun testDecodeDisplaySpecInfo() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeByte(0)            // subtype: display info
        dos.writeShort(0)           // pad
        dos.writeShort(0)           // pad
        dos.writeShort(800)         // width
        dos.writeShort(480)         // height

        val decoded = SACCodec.decode(SACCommand.OP_A2S_PROC_SPEC, bos.toByteArray())
        assertTrue(decoded is SACCommand.DisplaySpecInfo)
        val spec = decoded as SACCommand.DisplaySpecInfo
        assertEquals(800, spec.width)
        assertEquals(480, spec.height)
    }

    @Test
    fun testDecodeProductSpecInfo() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeByte(1)            // subtype: spec info
        dos.writeShort(0x1234)      // model ID
        dos.writeByte(2)            // pointer count (multi-touch)
        dos.writeByte(1)            // GPS
        dos.writeByte(1)            // remote ctrl
        dos.writeByte(0)            // CAN bus
        dos.writeByte(0x80)         // flagByte (MSB set -> isCalib true)

        val decoded = SACCodec.decode(SACCommand.OP_A2S_PROC_SPEC, bos.toByteArray())
        assertTrue(decoded is SACCommand.ProductSpecInfo)
        val prod = decoded as SACCommand.ProductSpecInfo
        assertEquals(0x1234.toShort(), prod.modelId)
        assertEquals(2.toByte(), prod.pointerCount)
        assertTrue(prod.hasGps)
        assertTrue(prod.hasRemoteControl)
        assertTrue(prod.isCalibrationTouch)
    }

    @Test
    fun testDecodeAccessoryStatus() {
        val payload = byteArrayOf(
            0x20,                   // subtype: status (32)
            0x03                    // flags: bit 0 (parking brake), bit 1 (HDMI)
        )
        val decoded = SACCodec.decode(SACCommand.OP_A2S_PACKAGEINFO, payload)
        assertTrue(decoded is SACCommand.AccessoryStatus)
        val status = decoded as SACCommand.AccessoryStatus
        assertTrue(status.isParkingBrakeOn)
        assertTrue(status.isHdmiConnected)
    }

    @Test
    fun testEncodeVideoOutputReply() {
        val encoded = SACCodec.encode(SACCommand.VideoOutputReply)
        assertArrayEquals(byteArrayOf(0x06, 0x01), encoded)
    }
}
