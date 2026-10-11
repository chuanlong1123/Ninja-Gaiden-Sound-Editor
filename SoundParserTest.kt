package com.ngsoundeditor.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundParserTest {
    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v ushr 8) and 0xff).toByte()
    }
    private fun put32(b: ByteArray, o: Int, v: Int) {
        repeat(4) { b[o + it] = ((v ushr (8 * it)) and 0xff).toByte() }
    }

    private fun syntheticXws(): ByteArray {
        val bodyOffset = 0x100
        val blockSize = 22
        val b = ByteArray(bodyOffset + blockSize)
        "XWSFILE".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        put32(b, 0x10, b.size)
        put32(b, 0x14, 2)
        put32(b, 0x18, 2)
        put32(b, 0x20, 0x30)
        put32(b, 0x24, 0x40)
        put32(b, 0x30, 0x50)
        put32(b, 0x34, bodyOffset)
        put32(b, 0x40, 0xB0)
        put32(b, 0x44, blockSize)

        val kwb = 0x50
        "KWB2".toByteArray(Charsets.US_ASCII).copyInto(b, kwb)
        put16(b, kwb + 6, 1)
        put32(b, kwb + 8, blockSize)
        put32(b, kwb + 0x18, 0x1c)

        val record = 0x6c
        put16(b, record, 0xB000)
        b[record + 3] = 1
        val meta = record + 0x2c
        put16(b, meta, 22050)
        b[meta + 2] = 0x10
        b[meta + 3] = 1
        put16(b, meta + 4, blockSize)
        put16(b, meta + 6, 32)
        put32(b, meta + 0x0c, 32)
        put32(b, meta + 0x10, 0)
        put32(b, meta + 0x14, blockSize)

        b[bodyOffset] = 0
        put16(b, bodyOffset + 1, 16)
        put16(b, bodyOffset + 3, 0)
        put16(b, bodyOffset + 5, 0)
        return b
    }

    private fun shortenWav(wav: ByteArray, frames: Int): ByteArray {
        val dataSize = frames * 2
        val out = ByteArray(44 + dataSize)
        wav.copyInto(out, 0, 0, 44)
        wav.copyInto(out, 44, 44, 44 + dataSize)
        put32(out, 4, 36 + dataSize)
        put32(out, 40, dataSize)
        return out
    }

    @Test fun parsesAndExportsSyntheticXws() {
        val bytes = syntheticXws()
        val bank = SoundParser.parse(bytes)
        assertEquals("XWS / KWB2", bank.format)
        assertEquals(1, bank.entries.size)
        assertTrue(bank.safeBodyLayout)
        val wav = SoundParser.decodeToWav(bytes, bank.entries.single())
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(32L, bank.entries.single().samples)
    }

    @Test fun scansEmbeddedXws() {
        val embedded = syntheticXws()
        val container = ByteArray(128) { 0x55.toByte() } + embedded + ByteArray(12) { 0x11.toByte() }
        val candidates = SoundParser.findEmbeddedCandidates(container)
        assertEquals(1, candidates.size)
        assertEquals(128, candidates.single().offset)
        assertEquals(1, candidates.single().bank.entries.size)
    }

    @Test fun variableLengthXwsRepackUpdatesSizesAndOffsets() {
        val bytes = syntheticXws()
        val bank = SoundParser.parse(bytes)
        val entry = bank.entries.single()
        val fullWav = SoundParser.decodeToWav(bytes, entry)
        val shortWav = shortenWav(fullWav, 16)
        val out = SoundParser.replaceEntryWithXwsRepack(bytes, bank, entry, shortWav)
        val parsed = SoundParser.parse(out)
        val updated = parsed.entries.single()
        assertEquals(16L, updated.samples)
        assertEquals(24L, parsed.bodySize)
        assertEquals(parsed.logicalSize, out.size.toLong())
        assertTrue(parsed.safeBodyLayout)
    }

    private fun syntheticSnd(): ByteArray {
        val td = 0x40
        val hbw = td + 0x80
        val dbw = td + 0xf0
        val kwb = hbw + 0x0c
        val body = dbw + 0x0c
        val bodyCapacity = 128
        val b = ByteArray(body + bodyCapacity)
        byteArrayOf(0x53, 0x4e, 0x44, 0x00).copyInto(b, 0)
        put32(b, 0x10, b.size)
        put32(b, 0x18, 1)
        put32(b, 0x20, 0x28)
        put32(b, 0x24, 0x2c)
        put32(b, 0x28, td)
        put32(b, 0x2c, b.size - td)

        "tdpack".toByteArray(Charsets.US_ASCII).copyInto(b, td)
        put32(b, td + 0x10, b.size - td)
        put32(b, td + 0x14, 2)
        put32(b, td + 0x18, 2)
        put32(b, td + 0x20, 0x28)
        put32(b, td + 0x24, 0x30)
        put32(b, td + 0x28, 0x80)
        put32(b, td + 0x2c, 0xf0)
        put32(b, td + 0x30, 0x50)
        put32(b, td + 0x34, bodyCapacity + 0x0c)

        "_HBW".toByteArray(Charsets.US_ASCII).copyInto(b, hbw)
        "KWB2".toByteArray(Charsets.US_ASCII).copyInto(b, kwb)
        put16(b, kwb + 6, 1)
        put32(b, kwb + 8, bodyCapacity)
        put32(b, kwb + 0x18, 0x1c)
        val record = kwb + 0x1c
        put16(b, record, 0xB000)
        b[record + 3] = 1
        val meta = record + 0x2c
        put16(b, meta, 22050)
        b[meta + 2] = 0x10
        b[meta + 3] = 1
        put16(b, meta + 4, 22)
        put16(b, meta + 6, 32)
        put32(b, meta + 0x0c, 32)
        put32(b, meta + 0x10, 0)
        put32(b, meta + 0x14, 22)
        "_DBW".toByteArray(Charsets.US_ASCII).copyInto(b, dbw)
        b[body] = 0
        put16(b, body + 1, 16)
        put16(b, body + 3, 0)
        put16(b, body + 5, 0)
        return b
    }

    @Test fun variableLengthSndRepackUpdatesOffsetsAndSampleCount() {
        val bytes = syntheticSnd()
        val bank = SoundParser.parse(bytes)
        assertTrue(bank.format.startsWith("SND"))
        assertTrue(bank.safeBodyLayout)
        val entry = bank.entries.single()
        val wav = SoundParser.decodeToWav(bytes, entry)
        val shortWav = shortenWav(wav, 16)
        val out = SoundParser.replaceEntryWithSndRepack(bytes, bank, entry, shortWav)
        val parsed = SoundParser.parse(out)
        assertEquals(16L, parsed.entries.single().samples)
        assertTrue(parsed.safeBodyLayout)
        assertEquals(bytes.size, out.size)
    }

    @Test fun rejectsPcmWavWithInvalidBlockAlignment() {
        val bankBytes = syntheticXws()
        val bank = SoundParser.parse(bankBytes)
        val wav = SoundParser.decodeToWav(bankBytes, bank.entries.single())
        // PCM16 mono must use a 2-byte block alignment.
        put16(wav, 32, 4)
        var rejected = false
        try { SoundParser.readPcmWav(wav) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
