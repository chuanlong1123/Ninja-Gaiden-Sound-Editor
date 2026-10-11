package com.ngsoundeditor.android

import java.util.Locale

data class SoundEntry(
    val globalIndex: Int, val soundRecord: Int, val subIndex: Int, val name: String,
    val metaAbs: Long, val rate: Int, val codec: Int, val channels: Int, val blockSize: Int,
    val samplesPerFrame: Int, val samples: Long, val audioRel: Long,
    val audioAbs: Long, val audioSize: Long, val duration: Double
)

data class SoundBank(
    val format: String, val logicalSize: Long, val physicalSize: Long,
    val soundRecords: Int, val entries: List<SoundEntry>,
    val headerOffset: Long, val bodyOffset: Long, val bodySize: Long,
    val safeBodyLayout: Boolean, val notes: List<String>
)

/** Read-only parser ported from the structural checks in the desktop PowerShell tool. */
object SoundParser {
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    private fun u16(b: ByteArray, o: Int) =
        (u8(b,o) or (u8(b,o+1) shl 8))
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b,o).toLong() or (u8(b,o+1).toLong() shl 8) or
         (u8(b,o+2).toLong() shl 16) or (u8(b,o+3).toLong() shl 24))
    private fun ascii(b: ByteArray, o: Int, n: Int) =
        String(b, o, n, Charsets.US_ASCII)
    private fun requireRange(b: ByteArray, o: Long, n: Long, what: String) {
        require(o >= 0 && n >= 0 && o <= b.size.toLong() && n <= b.size.toLong()-o) {
            "$what is outside the file (offset 0x${o.toString(16)}, size 0x${n.toString(16)})."
        }
    }
    private fun zero(b: ByteArray, start: Int, length: Int): Boolean {
        if (start < 0 || length < 0 || start.toLong()+length > b.size.toLong()) return false
        for (i in start until start+length) if (b[i].toInt()!=0) return false
        return true
    }

    data class EmbeddedCandidate(val offset: Int, val bytes: ByteArray, val bank: SoundBank)

    /**
     * Scan a DAT-like container for structurally valid embedded SND/XWS banks.
     * Candidates are only returned after the normal strict parser accepts a bounded slice.
     */
    fun findEmbeddedCandidates(container: ByteArray, maxCandidates: Int = 16): List<EmbeddedCandidate> {
        val found = mutableListOf<EmbeddedCandidate>()
        var i = 0
        while (i + 0x80 <= container.size && found.size < maxCandidates) {
            val isSnd = i + 4 <= container.size && ascii(container, i, 4) == "SND\u0000"
            val isXws = i + 7 <= container.size && ascii(container, i, 7) == "XWSFILE"
            if (!isSnd && !isXws) { i++; continue }
            try {
                val logical = u32(container, i + 0x10)
                if (logical < 0x80 || logical > container.size - i) { i++; continue }
                val slice = container.copyOfRange(i, i + logical.toInt())
                val bank = parse(slice)
                found += EmbeddedCandidate(i, slice, bank)
                i += logical.toInt()
            } catch (_: Exception) {
                i++
            }
        }
        return found
    }

    fun parse(b: ByteArray): SoundBank {
        require(b.size >= 0x80) { "File is too small to be a supported SND/XWS bank." }
        return when {
            ascii(b,0,4) == "SND\u0000" -> parseSnd(b)
            ascii(b,0,7) == "XWSFILE" -> parseXws(b)
            else -> throw IllegalArgumentException("Unsupported header. Expected SND\\0 or XWSFILE.")
        }
    }

    private fun parseXws(b: ByteArray): SoundBank {
        val logical = u32(b,0x10)
        require(logical >= 0x80 && logical <= b.size.toLong()) { "XWS logical size is invalid." }
        val notes = mutableListOf<String>()
        if (logical != b.size.toLong()) {
            require((logical until b.size.toLong()).all { b[it.toInt()] == 0x30.toByte() }) {
                "Physical size differs from logical size and trailing data is not all 0x30 padding."
            }
            notes += "File has ${b.size-logical} trailing 0x30 padding bytes; preserved by read-only inspection."
        }
        val chunks2=u32(b,0x14); val chunks=u32(b,0x18)
        val t1=u32(b,0x20); val t2=u32(b,0x24)
        require(chunks in 2L..128L && chunks2==chunks) { "Unsupported XWS chunk table." }
        requireRange(b,t1,chunks*4,"XWS offset table")
        requireRange(b,t2,chunks*4,"XWS size table")
        val offsets=(0 until chunks.toInt()).map { u32(b,(t1+it*4).toInt()) }
        val sizes=(0 until chunks.toInt()).map { u32(b,(t2+it*4).toInt()) }
        val ki=offsets.indexOfFirst { o -> o+4 <= logical && o+4 <= b.size.toLong() && ascii(b,o.toInt(),4)=="KWB2" }
        require(ki>=0 && ki+1<offsets.size) { "Could not find KWB2 followed by its audio body." }
        val kwb=offsets[ki]; val body=offsets[ki+1]; val bodySize=sizes[ki+1]
        requireRange(b,kwb,0x1c,"KWB2 header")
        requireRange(b,body,bodySize,"XWS audio body")
        require(body+bodySize<=logical) { "XWS body exceeds logical file size." }
        val parsed=parseKwb(b,kwb,body,bodySize,null)
        return SoundBank("XWS / KWB2",logical,b.size.toLong(),parsed.first,parsed.second,
            kwb,body,bodySize,parsed.third,notes)
    }

    private fun parseSnd(b: ByteArray): SoundBank {
        require(b.size>=0x100) { "SND file is too small." }
        val logical=u32(b,0x10)
        require(logical==b.size.toLong()) { "SND header size does not match physical file size." }
        val chunks=u32(b,0x18)
        val t1=u32(b,0x20); val t2=u32(b,0x24)
        require(chunks in 1L..64L) { "Invalid SND outer chunk count." }
        requireRange(b,t1,chunks*4,"SND offset table")
        requireRange(b,t2,chunks*4,"SND size table")
        val offsets=(0 until chunks.toInt()).map { u32(b,(t1+it*4).toInt()) }
        val sizes=(0 until chunks.toInt()).map { u32(b,(t2+it*4).toInt()) }
        val tdIndex=offsets.indexOfFirst { o -> o+8<=b.size && ascii(b,o.toInt(),6)=="tdpack" }
        require(tdIndex>=0) { "Could not detect tdpack in this SND." }
        val td=offsets[tdIndex]
        requireRange(b,td,0x28,"tdpack header")
        val tdChunks2=u32(b,(td+0x14).toInt()); val tdChunks=u32(b,(td+0x18).toInt())
        val tt1=u32(b,(td+0x20).toInt()); val tt2=u32(b,(td+0x24).toInt())
        require(tdChunks in 2L..64L && tdChunks2==tdChunks) { "Unsupported tdpack chunk table." }
        requireRange(b,td+tt1,tdChunks*4,"tdpack offset table")
        requireRange(b,td+tt2,tdChunks*4,"tdpack size table")
        val to=(0 until tdChunks.toInt()).map { u32(b,(td+tt1+it*4).toInt()) }
        val hi=to.indexOfFirst { o -> td+o+4<=b.size.toLong() && ascii(b,(td+o).toInt(),4)=="_HBW" }
        require(hi>=0 && hi+1<to.size) { "Could not find _HBW/_DBW chunk pair." }
        val hbw=td+to[hi]; val dbw=td+to[hi+1]
        requireRange(b,dbw,4,"_DBW header")
        require(ascii(b,dbw.toInt(),4)=="_DBW") { "_DBW does not follow _HBW." }
        requireRange(b,hbw+0x0c,0x1c,"KWB2 header")
        val kwb=hbw+0x0c
        require(ascii(b,kwb.toInt(),4)=="KWB2") { "KWB2 not found inside _HBW." }
        val body=dbw+0x0c
        val bodySize=u32(b,(kwb+0x08).toInt())
        requireRange(b,body,bodySize,"SND audio body")
        val soundCount=u16(b,(kwb+0x06).toInt())
        val names=extractSourceNames(b,kwb,soundCount,offsets,sizes,tdIndex)
        val parsed=parseKwb(b,kwb,body,bodySize,names)
        return SoundBank("SND / tdpack / _HBW+_DBW / KWB2",logical,b.size.toLong(),
            parsed.first,parsed.second,kwb,body,bodySize,parsed.third,
            listOf("Detected ${names.size} named sound slots from SOURCE_SE strings."))
    }

    private fun extractSourceNames(b:ByteArray, kwb:Long, soundCount:Int,
        offsets:List<Long>, sizes:List<Long>, skip:Int):Map<Int,String> {
        val map=mutableMapOf<Int,MutableList<String>>()
        val regex=Regex("SOURCE_SE_[A-Z0-9_]+")
        for (ci in offsets.indices) {
            if(ci==skip) continue
            val start=offsets[ci]; val len=sizes[ci]
            if(start<0 || len<=0 || start+len>b.size.toLong()) continue
            val s=ascii(b,start.toInt(),len.toInt())
            for(m in regex.findAll(s)) {
                val abs=start+m.range.first
                val aligned=((abs+m.value.length+1+3) and 0x7ffffffcL)
                if(aligned+16>b.size) continue
                val code=u32(b,(aligned+12).toInt())
                val family=code and 0xff000000L
                val slot=(code and 0xff).toInt()
                if((family==0x20000000L || family==0x21000000L) && slot in 0 until soundCount) {
                    val rel=u32(b,(kwb+0x18+slot*4).toInt())
                    if(rel!=0L) map.getOrPut(slot){ mutableListOf() }.add(m.value)
                }
            }
        }
        return map.mapValues { it.value.distinct().joinToString(" | ") }
    }

    private fun parseKwb(b:ByteArray, kwb:Long, body:Long, bodySize:Long,
        names:Map<Int,String>?):Triple<Int,List<SoundEntry>,Boolean> {
        requireRange(b,kwb,0x1c,"KWB2 header")
        require(ascii(b,kwb.toInt(),4)=="KWB2") { "Invalid KWB2 signature." }
        val sounds=u16(b,(kwb+6).toInt())
        require(sounds in 1..4096) { "Invalid KWB2 sound record count." }
        val entries=mutableListOf<SoundEntry>()
        for(i in 0 until sounds) {
            val rel=u32(b,(kwb+0x18+i*4).toInt())
            if(rel==0L) continue
            val so=kwb+rel
            requireRange(b,so,0x30,"KWB2 sound record ${i+1}")
            require(so+0x30<=body) { "KWB2 sound record ${i+1} overlaps the audio body." }
            val version=u16(b,so.toInt())
            val subs=u8(b,(so+3).toInt())
            val subStart:Long; val subSize:Long
            if(version<0xc000) { subStart=0x2c; subSize=0x48 }
            else { subStart=u16(b,(so+0x2c).toInt()).toLong(); subSize=u16(b,(so+0x2e).toInt()).toLong() }
            if(subs<=0 || subSize<0x18) continue
            for(j in 0 until subs) {
                val ss=so+subStart+j*subSize
                requireRange(b,ss,0x18,"Subsound metadata")
                require(ss+0x18<=body) { "Subsound metadata overlaps the audio body." }
                val rate=u16(b,ss.toInt()); val codec=u8(b,(ss+2).toInt())
                val ch=u8(b,(ss+3).toInt()); val block=u16(b,(ss+4).toInt())
                val spf=u16(b,(ss+6).toInt()); val samples=u32(b,(ss+0x0c).toInt())
                val ar=u32(b,(ss+0x10).toInt()); val asz=u32(b,(ss+0x14).toInt())
                require(ar<=bodySize && asz<=bodySize-ar) { "Audio range outside body at record ${i+1}, sub ${j+1}." }
                entries += SoundEntry(entries.size+1,i+1,j+1,names?.get(i) ?: "",ss,rate,codec,ch,block,spf,samples,
                    ar,body+ar,asz,if(rate>0) samples.toDouble()/rate else 0.0)
            }
        }
        var safe=true; var cursor=0L
        for(e in entries.sortedBy { it.audioRel }) {
            if(e.audioRel<cursor) { safe=false; break }
            val gap=e.audioRel-cursor
            if(gap>0 && !zero(b,(body+cursor).toInt(),gap.toInt())) { safe=false; break }
            cursor=e.audioRel+e.audioSize
        }
        if(safe && cursor<=bodySize && bodySize-cursor>0)
            safe=zero(b,(body+cursor).toInt(),(bodySize-cursor).toInt())
        return Triple(sounds,entries,safe)
    }


    data class PcmWav(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun readPcmWav(wav: ByteArray): PcmWav {
        require(wav.size >= 44 && ascii(wav, 0, 4) == "RIFF" && ascii(wav, 8, 4) == "WAVE") {
            "Input must be a RIFF/WAVE file."
        }
        val riffSize = u32(wav, 4)
        require(riffSize >= 4 && riffSize + 8 <= wav.size.toLong()) { "RIFF size is invalid or truncated." }
        val riffEnd = (riffSize + 8).toInt()
        var pos = 12
        var format = -1
        var channels = 0
        var rate = 0
        var byteRate = 0L
        var blockAlign = 0
        var bits = 0
        var data: ByteArray? = null
        while (pos + 8 <= riffEnd) {
            val id = ascii(wav, pos, 4)
            val len = u32(wav, pos + 4)
            val start = pos + 8
            require(len <= riffEnd.toLong() - start) { "Truncated WAV chunk '$id'." }
            when (id) {
                "fmt " -> {
                    require(len >= 16) { "WAV fmt chunk is too short." }
                    format = u16(wav, start)
                    channels = u16(wav, start + 2)
                    rate = u32(wav, start + 4).toInt()
                    byteRate = u32(wav, start + 8)
                    blockAlign = u16(wav, start + 12)
                    bits = u16(wav, start + 14)
                }
                "data" -> data = wav.copyOfRange(start, start + len.toInt())
            }
            pos = start + len.toInt() + (len.toInt() and 1)
        }
        require(format == 1 && bits == 16) { "Only uncompressed PCM16 WAV is supported." }
        require(channels in 1..2) { "Only mono or stereo WAV is supported for this replacement path." }
        require(rate in 8000..192000) { "Unsupported WAV sample rate." }
        val expectedAlign = channels * 2
        require(blockAlign == expectedAlign && byteRate == rate.toLong() * expectedAlign) {
            "WAV block alignment or byte rate does not match PCM16 channel geometry."
        }
        val bytes = data ?: throw IllegalArgumentException("WAV data chunk was not found.")
        require(bytes.size % blockAlign == 0) { "PCM16 data does not contain a whole number of frames." }
        val samples = ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return PcmWav(rate, channels, samples)
    }

    /** Encode mono PCM16 samples to standard MS-ADPCM blocks with fixed block size. */
    fun encodeMsAdpcmMono(samples: ShortArray, blockSize: Int): ByteArray {
        require(blockSize >= 8) { "MS-ADPCM block size must be at least 8 bytes." }
        val samplesPerBlock = 2 + (blockSize - 7) * 2
        val blockCount = (samples.size + samplesPerBlock - 1) / samplesPerBlock
        require(blockCount > 0) { "WAV contains no samples." }
        val output = ByteArray(blockCount * blockSize)
        val c1 = intArrayOf(256,512,0,192,240,460,392)
        val c2 = intArrayOf(0,-256,0,64,0,-208,-232)
        val adapt = intArrayOf(230,230,230,230,307,409,512,614,768,614,512,409,307,230,230,230)
        var sampleCursor = 0
        for (bi in 0 until blockCount) {
            val off = bi * blockSize
            val remaining = samples.size - sampleCursor
            val count = minOf(samplesPerBlock, remaining)
            val first = samples[sampleCursor].toInt()
            val second = if (count >= 2) samples[sampleCursor + 1].toInt() else first
            // Pick the standard predictor that best estimates the third sample.
            var predictor = 0
            if (count >= 3) {
                var bestError = Long.MAX_VALUE
                for (p in c1.indices) {
                    val pred = (first * c1[p] + second * c2[p]) / 256
                    val err = kotlin.math.abs(samples[sampleCursor + 2].toInt().toLong() - pred)
                    if (err < bestError) { bestError = err; predictor = p }
                }
            }
            output[off] = predictor.toByte()
            var delta = if (count >= 3) maxOf(16, kotlin.math.abs(samples[sampleCursor + 2].toInt() - ((first*c1[predictor]+second*c2[predictor])/256))) else 16
            put16(output, off + 1, delta)
            put16(output, off + 3, second)
            put16(output, off + 5, first)
            var s1 = second
            var s2 = first
            var nibbleCount = 0
            var packed = 0
            var idx = sampleCursor + 2
            while (idx < sampleCursor + count) {
                val predicted = (s1 * c1[predictor] + s2 * c2[predictor]) / 256
                val diff = samples[idx].toInt() - predicted
                val nib = ((diff.toDouble() / delta).let { kotlin.math.round(it).toInt() }).coerceIn(-8, 7)
                val code = nib and 0x0f
                val reconstructed = (predicted + nib * delta).coerceIn(-32768, 32767)
                s2 = s1
                s1 = reconstructed
                delta = maxOf(16, adapt[code] * delta / 256)
                if (nibbleCount == 0) {
                    packed = code shl 4
                    nibbleCount = 1
                } else {
                    packed = packed or code
                    val byteIndex = off + 7 + ((idx - (sampleCursor + 2)) / 2)
                    if (byteIndex < off + blockSize) output[byteIndex] = packed.toByte()
                    nibbleCount = 0
                }
                idx++
            }
            sampleCursor += count
        }
        return output
    }

    private fun put16(b: ByteArray, o: Int, value: Int) {
        b[o] = (value and 0xff).toByte()
        b[o + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun put32(b: ByteArray, o: Int, value: Long) {
        repeat(4) { b[o + it] = ((value ushr (8 * it)) and 0xff).toByte() }
    }

    /**
     * SND-only variable-length replacement. Rebuilds the DBW body with 8-byte alignment,
     * rewrites every audio offset/size, and refuses output exceeding the existing fixed body pool.
     */
    /** XWS variable-length replacement: body is rebuilt and the final logical file size/table are updated. */
    fun replaceEntryWithXwsRepack(file: ByteArray, bank: SoundBank, entry: SoundEntry, wav: ByteArray): ByteArray {
        require(bank.format.startsWith("XWS")) { "Variable-length XWS repack requires an XWS bank." }
        require(bank.bodyOffset + bank.bodySize == bank.logicalSize) {
            "XWS repack currently requires the audio body to be the final logical chunk."
        }
        require(bank.safeBodyLayout) { "Bank body contains unknown non-zero gaps/overlap; repack is disabled." }
        require(entry.codec == 0x10 && entry.channels == 1) { "XWS repack currently supports mono MS-ADPCM only." }
        requireSupportedMsAdpcmGeometry(entry)
        val pcm = readPcmWav(wav)
        require(pcm.channels == 1) { "Replacement WAV must be mono." }
        require(pcm.sampleRate == entry.rate) { "WAV sample rate (${pcm.sampleRate}) must match target (${entry.rate})." }
        val encoded = encodeMsAdpcmMono(pcm.samples, entry.blockSize)
        require(encoded.isNotEmpty() && encoded.size % entry.blockSize == 0) { "Encoded audio does not align to target MS-ADPCM blocks." }

        val sorted = bank.entries.sortedBy { it.audioRel }
        val bodyStream = java.io.ByteArrayOutputStream()
        val newRanges = mutableMapOf<Int, Pair<Long, Long>>()
        for (e in sorted) {
            while ((bodyStream.size() % 8) != 0) bodyStream.write(0)
            val rel = bodyStream.size().toLong()
            val raw = if (e.globalIndex == entry.globalIndex) encoded else {
                require(e.audioAbs >= 0 && e.audioAbs + e.audioSize <= file.size) { "Existing audio range is invalid for #${e.globalIndex}." }
                file.copyOfRange(e.audioAbs.toInt(), (e.audioAbs + e.audioSize).toInt())
            }
            newRanges[e.globalIndex] = rel to raw.size.toLong()
            bodyStream.write(raw)
        }
        while ((bodyStream.size() % 8) != 0) bodyStream.write(0)
        val rebuilt = bodyStream.toByteArray()
        val bodyStart = bank.bodyOffset.toInt()
        require(bodyStart >= 0 && bodyStart <= file.size) { "XWS body offset is invalid." }
        val logicalLength = bodyStart.toLong() + rebuilt.size
        val hasTrailingPad = bank.physicalSize > bank.logicalSize
        if (hasTrailingPad) require(logicalLength <= bank.physicalSize) {
            "Repacked XWS needs $logicalLength logical bytes but padded physical capacity is ${bank.physicalSize}."
        }
        require(logicalLength <= Int.MAX_VALUE) { "Repacked XWS exceeds supported file size." }
        val physicalLength = if (hasTrailingPad) bank.physicalSize.toInt() else logicalLength.toInt()
        val out = ByteArray(physicalLength)
        System.arraycopy(file, 0, out, 0, bodyStart)
        System.arraycopy(rebuilt, 0, out, bodyStart, rebuilt.size)
        if (hasTrailingPad) java.util.Arrays.fill(out, logicalLength.toInt(), out.size, 0x30.toByte())

        val chunkCount = u32(file, 0x18).toInt()
        val table1 = u32(file, 0x20).toInt()
        val table2 = u32(file, 0x24).toInt()
        requireRange(file, table1.toLong(), chunkCount * 4L, "XWS offset table")
        requireRange(file, table2.toLong(), chunkCount * 4L, "XWS size table")
        val bodyIndex = (0 until chunkCount).firstOrNull { u32(file, table1 + it * 4) == bank.bodyOffset }
            ?: throw IllegalArgumentException("XWS body chunk index not found.")
        put32(out, table2 + bodyIndex * 4, rebuilt.size.toLong())
        put32(out, 0x10, logicalLength)
        put32(out, bank.headerOffset.toInt() + 0x08, rebuilt.size.toLong())
        for (e in bank.entries) {
            val range = newRanges[e.globalIndex] ?: throw IllegalStateException("Missing rebuilt range for #${e.globalIndex}.")
            val meta = e.metaAbs.toInt()
            put32(out, meta + 0x10, range.first)
            put32(out, meta + 0x14, range.second)
            if (e.globalIndex == entry.globalIndex) put32(out, meta + 0x0c, pcm.samples.size.toLong())
        }
        val verified = parse(out)
        require(verified.entries.size == bank.entries.size) { "Repacked XWS failed entry-count verification." }
        require(verified.entries.all { it.audioAbs >= verified.bodyOffset && it.audioAbs + it.audioSize <= verified.bodyOffset + verified.bodySize }) {
            "Repacked XWS contains an audio range outside the body."
        }
        return out
    }

    fun replaceEntryWithSndRepack(file: ByteArray, bank: SoundBank, entry: SoundEntry, wav: ByteArray): ByteArray {
        require(bank.format.startsWith("SND")) { "Variable-length repack is currently SND-only." }
        require(bank.bodyOffset + bank.bodySize == bank.physicalSize) {
            "SND repack currently requires the fixed audio body pool to reach the end of the file."
        }
        require(bank.safeBodyLayout) { "Bank body contains unknown non-zero gaps/overlap; repack is disabled." }
        require(entry.codec == 0x10 && entry.channels == 1) { "Repack replacement currently supports mono MS-ADPCM only." }
        requireSupportedMsAdpcmGeometry(entry)
        val pcm = readPcmWav(wav)
        require(pcm.channels == 1) { "Replacement WAV must be mono." }
        require(pcm.sampleRate == entry.rate) { "WAV sample rate (${pcm.sampleRate}) must match target (${entry.rate})." }
        val encoded = encodeMsAdpcmMono(pcm.samples, entry.blockSize)
        require(encoded.isNotEmpty() && encoded.size % entry.blockSize == 0) { "Encoded audio does not align to target MS-ADPCM blocks." }

        val sorted = bank.entries.sortedBy { it.audioRel }
        val bodyStream = java.io.ByteArrayOutputStream()
        val newRanges = mutableMapOf<Int, Pair<Long, Long>>()
        for (e in sorted) {
            while ((bodyStream.size() % 8) != 0) bodyStream.write(0)
            val rel = bodyStream.size().toLong()
            val raw = if (e.globalIndex == entry.globalIndex) encoded else {
                require(e.audioAbs >= 0 && e.audioAbs + e.audioSize <= file.size) { "Existing audio range is invalid for #${e.globalIndex}." }
                file.copyOfRange(e.audioAbs.toInt(), (e.audioAbs + e.audioSize).toInt())
            }
            newRanges[e.globalIndex] = rel to raw.size.toLong()
            bodyStream.write(raw)
        }
        while ((bodyStream.size() % 8) != 0) bodyStream.write(0)
        val rebuilt = bodyStream.toByteArray()
        require(rebuilt.size.toLong() <= bank.bodySize) {
            "Repacked audio needs ${rebuilt.size} bytes but the SND body pool has ${bank.bodySize} bytes."
        }
        require(bank.bodyOffset >= 0 && bank.bodyOffset + bank.bodySize <= file.size) { "SND body pool is outside file." }
        val out = file.copyOf()
        java.util.Arrays.fill(out, bank.bodyOffset.toInt(), (bank.bodyOffset + bank.bodySize).toInt(), 0.toByte())
        System.arraycopy(rebuilt, 0, out, bank.bodyOffset.toInt(), rebuilt.size)
        for (e in bank.entries) {
            val range = newRanges[e.globalIndex] ?: throw IllegalStateException("Missing rebuilt range for #${e.globalIndex}.")
            val meta = e.metaAbs.toInt()
            put32(out, meta + 0x10, range.first)
            put32(out, meta + 0x14, range.second)
            if (e.globalIndex == entry.globalIndex) put32(out, meta + 0x0c, pcm.samples.size.toLong())
        }
        val verified = parse(out)
        require(verified.entries.size == bank.entries.size) { "Repacked SND failed entry-count verification." }
        require(verified.entries.all { it.audioAbs >= bank.bodyOffset && it.audioAbs + it.audioSize <= bank.bodyOffset + bank.bodySize }) {
            "Repacked SND contains an audio range outside the body pool."
        }
        return out
    }

    /** True when a same-size overwrite cannot alter another indexed stream's audio range. */
    fun isEntryRangeExclusive(bank: SoundBank, entry: SoundEntry): Boolean {
        if (entry.audioSize <= 0 || entry.audioRel < 0 || entry.audioRel + entry.audioSize > bank.bodySize) return false
        return bank.entries.filter { it.globalIndex != entry.globalIndex }.none { other ->
            other.audioSize > 0 &&
                entry.audioRel < other.audioRel + other.audioSize &&
                other.audioRel < entry.audioRel + entry.audioSize
        }
    }

    private fun requireSupportedMsAdpcmGeometry(entry: SoundEntry) {
        require((entry.blockSize == 22 && entry.samplesPerFrame == 32) ||
            (entry.blockSize == 38 && entry.samplesPerFrame == 64)) {
            "MS-ADPCM replacement supports only HQ22 (22-byte/32-sample) or COMPACT64 (38-byte/64-sample) geometry."
        }
    }

    fun replaceEntryWithWav(file: ByteArray, bank: SoundBank, entry: SoundEntry, wav: ByteArray): ByteArray {
        require(isEntryRangeExclusive(bank, entry)) {
            "This stream overlaps another indexed audio range or lies outside the body; same-size replacement is disabled."
        }
        require((entry.codec == 0x10 || entry.codec == 0x00) &&
            (entry.channels == 1 || (entry.codec == 0x00 && entry.channels == 2))) {
            "Replacement currently supports mono MS-ADPCM and mono/stereo PCM16 entries only."
        }
        if (entry.codec == 0x10) requireSupportedMsAdpcmGeometry(entry)
        val pcm = readPcmWav(wav)
        require(pcm.sampleRate == entry.rate) { "WAV sample rate (${pcm.sampleRate}) must match target (${entry.rate})." }
        require(pcm.channels == entry.channels) { "WAV channel count (${pcm.channels}) must match target (${entry.channels})." }
        require(pcm.samples.size.toLong() == entry.samples * entry.channels) {
            "WAV contains ${pcm.samples.size / pcm.channels} frames; target requires ${entry.samples} frames. No resampling/trimming is done."
        }
        val encoded = when (entry.codec) {
            0x10 -> encodeMsAdpcmMono(pcm.samples, entry.blockSize)
            0x00 -> ByteArray(pcm.samples.size * 2).also { out ->
                pcm.samples.forEachIndexed { i, sample ->
                    out[i * 2] = (sample.toInt() and 0xff).toByte()
                    out[i * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
                }
            }
            else -> error("Unsupported codec.")
        }
        require(encoded.size.toLong() == entry.audioSize) {
            "Encoded data size (${encoded.size}) differs from target (${entry.audioSize}). This entry cannot be safely replaced without rebuilding offsets."
        }
        require(entry.audioAbs >= 0 && entry.audioAbs + entry.audioSize <= file.size) {
            "Target audio range is outside the file."
        }
        val copy = file.copyOf()
        System.arraycopy(encoded, 0, copy, entry.audioAbs.toInt(), encoded.size)
        // Confirm all structural metadata still parses after same-length replacement.
        parse(copy)
        return copy
    }

    fun codecName(codec:Int):String = when(codec) {
        0x00 -> "PCM16"; 0x10 -> "MS-ADPCM"; 0x90 -> "DSP"
        else -> "0x%02X".format(Locale.ROOT, codec)
    }

    /** Decode supported mono entries into standard PCM16 WAV. */
    fun decodeToWav(file: ByteArray, e: SoundEntry): ByteArray {
        return when (e.codec) {
            0x10 -> decodeMsAdpcmWav(file, e)
            0x00 -> {
                require(e.channels in 1..2) { "PCM16 export supports mono or stereo only." }
                require(e.audioAbs >= 0 && e.audioSize >= 0 && e.audioAbs + e.audioSize <= file.size) { "Audio range is outside the selected file." }
                require(e.audioSize == e.samples * e.channels * 2L) { "PCM16 data size does not match frame count." }
                val data = file.copyOfRange(e.audioAbs.toInt(), (e.audioAbs + e.audioSize).toInt())
                val out = java.io.ByteArrayOutputStream(44 + data.size)
                fun ascii(v: String) { out.write(v.toByteArray(Charsets.US_ASCII)) }
                fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
                fun le32(v: Long) { repeat(4) { out.write(((v ushr (8 * it)) and 0xff).toInt()) } }
                val align = e.channels * 2
                ascii("RIFF"); le32(36L + data.size); ascii("WAVE")
                ascii("fmt "); le32(16); le16(1); le16(e.channels); le32(e.rate.toLong())
                le32(e.rate.toLong() * align); le16(align); le16(16)
                ascii("data"); le32(data.size.toLong()); out.write(data)
                out.toByteArray()
            }
            else -> throw IllegalArgumentException("WAV export does not support ${codecName(e.codec)} yet.")
        }
    }

    /** Decode one mono MS-ADPCM KWB2 entry to a standard PCM16 RIFF/WAVE file. */
    fun decodeMsAdpcmWav(file: ByteArray, e: SoundEntry): ByteArray {
        require(e.codec == 0x10 && e.channels == 1) { "WAV export currently supports MS-ADPCM mono only." }
        require(e.blockSize >= 7 && e.samplesPerFrame >= 2) { "Invalid MS-ADPCM block geometry." }
        require(e.audioAbs >= 0 && e.audioSize >= 0 && e.audioAbs + e.audioSize <= file.size) { "Audio range is outside the selected file." }
        require(e.audioSize % e.blockSize == 0L) { "Audio size is not a whole number of MS-ADPCM blocks." }
        val c1 = intArrayOf(256,512,0,192,240,460,392)
        val c2 = intArrayOf(0,-256,0,64,0,-208,-232)
        val adapt = intArrayOf(230,230,230,230,307,409,512,614,768,614,512,409,307,230,230,230)
        val pcm = java.io.ByteArrayOutputStream()
        var written = 0L
        var blockOffset = e.audioAbs.toInt()
        val end = (e.audioAbs + e.audioSize).toInt()
        while (blockOffset < end && written < e.samples) {
            val len = minOf(e.blockSize, end - blockOffset)
            require(len == e.blockSize) { "Truncated MS-ADPCM block." }
            val predictor = file[blockOffset].toInt() and 0xff
            require(predictor < c1.size) { "Unsupported MS-ADPCM predictor $predictor." }
            var delta = u16(file, blockOffset + 1).coerceAtLeast(16)
            var sample1 = u16(file, blockOffset + 3).toShort().toInt()
            var sample2 = u16(file, blockOffset + 5).toShort().toInt()
            fun emit(v: Int) {
                if (written < e.samples) {
                    pcm.write(v and 0xff)
                    pcm.write((v ushr 8) and 0xff)
                    written++
                }
            }
            emit(sample2)
            emit(sample1)
            for (p in blockOffset + 7 until blockOffset + len) {
                val byte = file[p].toInt() and 0xff
                for (nibble in intArrayOf(byte ushr 4, byte and 0x0f)) {
                    if (written >= e.samples) break
                    val signedNibble = if (nibble >= 8) nibble - 16 else nibble
                    val predicted = ((sample1 * c1[predictor] + sample2 * c2[predictor]) / 256 + signedNibble * delta).coerceIn(-32768, 32767)
                    sample2 = sample1
                    sample1 = predicted
                    emit(predicted)
                    delta = maxOf(16, adapt[nibble] * delta / 256)
                }
            }
            blockOffset += len
        }
        require(written == e.samples) { "Decoded $written samples, expected ${e.samples}." }
        val data = pcm.toByteArray()
        val out = java.io.ByteArrayOutputStream(44 + data.size)
        fun ascii(v: String) { out.write(v.toByteArray(Charsets.US_ASCII)) }
        fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
        fun le32(v: Long) { repeat(4) { out.write(((v ushr (8 * it)) and 0xff).toInt()) } }
        ascii("RIFF"); le32(36L + data.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(e.rate.toLong())
        le32(e.rate.toLong() * 2); le16(2); le16(16)
        ascii("data"); le32(data.size.toLong()); out.write(data)
        return out.toByteArray()
    }
}
