package com.ngsoundeditor.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var status: TextView
    private var currentUri: Uri? = null
    private var loadedBytes: ByteArray? = null
    private var pendingWav: ByteArray? = null
    private var pendingWavName: String = "sound.wav"
    private var pendingReplaceEntry: SoundEntry? = null
    private var pendingBankOutput: ByteArray? = null
    private var pendingBankName: String = "NGSE_replaced.SND"
    private var pendingRawBank: ByteArray? = null
    private var pendingRawName: String = "NGSE_extracted.SND"
    private lateinit var entryList: LinearLayout
    private lateinit var embeddedButton: Button
    private var embeddedCandidateIndex = 0
    private var embeddedCandidateCount = 0
    @Volatile private var activeTrack: AudioTrack? = null
    private val stopRequested = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 20)
            setBackgroundColor(0xFF11151B.toInt())
        }
        outer.addView(TextView(this).apply {
            text = "NG SOUND EDITOR"
            textSize = 24f
            setTextColor(0xFFE8EDF5.toInt())
            gravity = Gravity.CENTER_VERTICAL
        })
        outer.addView(TextView(this).apply {
            text = "Android • SND / XWS / KWB2 inspector"
            textSize = 13f
            setTextColor(0xFF9EADBF.toInt())
            setPadding(0, 6, 0, 16)
        })
        outer.addView(Button(this).apply {
            text = "OPEN SND / XWS / DAT"
            setOnClickListener { openFile() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "RELOAD SOURCE"
            setOnClickListener {
                val uri = currentUri
                if (uri == null) status.text = "No source file selected."
                else {
                    embeddedCandidateIndex = 0
                    embeddedCandidateCount = 0
                    inspect(uri)
                }
            }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            text = "Open a SND/XWS bank to inspect, preview, export or replace supported audio."
            textSize = 13f
            setTextColor(0xFFB8C5D6.toInt())
            setPadding(0, 12, 0, 4)
        }
        outer.addView(status)
        outer.addView(Button(this).apply {
            text = "EXPORT BANK COPY"
            setOnClickListener { chooseBankCopyDestination() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "EXPORT ALL SUPPORTED WAVs (ZIP)"
            setOnClickListener { chooseBatchWavDestination() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "EXPORT SOUND MAP (CSV)"
            setOnClickListener { chooseSoundMapDestination() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "BATCH REPLACE WAVs (ZIP)"
            setOnClickListener { chooseBatchReplacementZip() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "BATCH REPACK WAVs (ZIP)"
            setOnClickListener { chooseBatchSndRepackZip() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        embeddedButton = Button(this).apply {
            text = "NEXT EMBEDDED BANK"
            isEnabled = false
            setOnClickListener {
                val uri = currentUri ?: return@setOnClickListener
                if (embeddedCandidateCount > 1) {
                    embeddedCandidateIndex = (embeddedCandidateIndex + 1) % embeddedCandidateCount
                    inspect(uri)
                }
            }
        }
        outer.addView(embeddedButton, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(Button(this).apply {
            text = "STOP PLAYBACK"
            setOnClickListener { stopPlayback() }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        output = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE0E6EF.toInt())
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        outer.addView(ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(-1, 1, 1f))
        outer.addView(TextView(this).apply {
            text = "EXPORT WAV (MS-ADPCM mono)"
            textSize = 14f
            setTextColor(0xFFE8EDF5.toInt())
            setPadding(0, 10, 0, 4)
        })
        entryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(ScrollView(this).apply { addView(entryList) },
            LinearLayout.LayoutParams(-1, 0, 1.2f))
        setContentView(outer)
    }

    private fun openFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, 1001)
    }

    @Deprecated("Kept for compatibility with the minimal prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val out = data?.data ?: return
            savePendingWav(out)
            return
        }
        if (requestCode == 1003 && resultCode == RESULT_OK) {
            val wavUri = data?.data ?: return
            replaceFromWav(wavUri)
            return
        }
        if (requestCode == 1004 && resultCode == RESULT_OK) {
            val out = data?.data ?: return
            savePendingBank(out)
            return
        }
        if (requestCode == 1005 && resultCode == RESULT_OK) {
            val out = data?.data ?: return
            saveRawBankCopy(out)
            return
        }
        if (requestCode == 1006 && resultCode == RESULT_OK) {
            val out = data?.data ?: return
            exportAllWavs(out)
            return
        }
        if (requestCode == 1007 && resultCode == RESULT_OK) {
            val out = data?.data ?: return
            exportSoundMap(out)
            return
        }
        if (requestCode == 1008 && resultCode == RESULT_OK) {
            val input = data?.data ?: return
            batchReplaceFromZip(input)
            return
        }
        if (requestCode == 1010 && resultCode == RESULT_OK) {
            val input = data?.data ?: return
            repackReplaceFromWav(input)
            return
        }
        if (requestCode == 1011 && resultCode == RESULT_OK) {
            val input = data?.data ?: return
            batchSndRepackFromZip(input)
            return
        }
        if (requestCode != 1001 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        currentUri = uri
        embeddedCandidateIndex = 0
        embeddedCandidateCount = 0
        embeddedButton.isEnabled = false
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        inspect(uri)
    }

    private fun inspect(uri: Uri) {
        status.text = "Reading file…"
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Could not open selected file.")
            require(bytes.size <= 128 * 1024 * 1024) { "File is over the 128 MiB prototype limit." }
            val originalName = queryName(uri)
            var name = originalName
            var bankBytes = bytes
            var extractedNote = ""
            val bank = try {
                embeddedCandidateCount = 0
                SoundParser.parse(bytes)
            } catch (directError: Exception) {
                val candidates = SoundParser.findEmbeddedCandidates(bytes)
                require(candidates.isNotEmpty()) {
                    "No directly readable SND/XWS bank found. ${directError.message}"
                }
                embeddedCandidateCount = candidates.size
                if (embeddedCandidateIndex !in candidates.indices) embeddedCandidateIndex = 0
                val selected = candidates[embeddedCandidateIndex]
                embeddedButton.isEnabled = candidates.size > 1
                embeddedButton.text = "NEXT EMBEDDED BANK (${embeddedCandidateIndex + 1}/${candidates.size})"
                bankBytes = selected.bytes
                name = "${originalName} (embedded bank @ 0x${selected.offset.toString(16)})"
                extractedNote = "Embedded bank ${embeddedCandidateIndex + 1}/${candidates.size} extracted in memory from container offset 0x${selected.offset.toString(16)}. Saving modifications exports this bank separately, not the original DAT."
                selected.bank
            }
            loadedBytes = bankBytes
            entryList.removeAllViews()
            val sha = MessageDigest.getInstance("SHA-256").digest(bankBytes)
                .joinToString("") { "%02x".format(it) }
            val sb = StringBuilder()
            sb.appendLine("FILE: $name")
            sb.appendLine("SIZE: ${bankBytes.size} bytes")
            if (extractedNote.isNotBlank()) sb.appendLine("NOTE: $extractedNote")
            sb.appendLine("SHA-256: $sha")
            sb.appendLine("FORMAT: ${bank.format}")
            sb.appendLine("LOGICAL / PHYSICAL: ${bank.logicalSize} / ${bank.physicalSize}")
            sb.appendLine("SOUND RECORDS: ${bank.soundRecords}")
            sb.appendLine("SUBSOUNDS: ${bank.entries.size}")
            sb.appendLine("KWB2 OFFSET: 0x${bank.headerOffset.toString(16)}")
            sb.appendLine("AUDIO BODY: 0x${bank.bodyOffset.toString(16)} (${bank.bodySize} bytes)")
            sb.appendLine("BODY LAYOUT: ${if(bank.safeBodyLayout) "no overlap; gaps/tail are zero-filled" else "non-zero gaps, overlap, or unsupported padding — do not repack"}")
            bank.notes.forEach { sb.appendLine("NOTE: $it") }
            sb.appendLine()
            sb.appendLine("SOUND / SUB LIST")
            sb.appendLine(" # | REC | SUB | RATE | CH | CODEC | SECONDS | AUDIO SIZE | NAME")
            sb.appendLine("-".repeat(100))
            for (e in bank.entries) {
                val nameText = e.name
                sb.appendLine(String.format(LocaleHolder.ROOT,
                    "%3d | %3d | %3d | %5d | %2d | %-12s | %7.2f | %9d | %s",
                    e.globalIndex,e.soundRecord,e.subIndex,e.rate,e.channels,
                    SoundParser.codecName(e.codec),e.duration,e.audioSize,nameText))
                sb.appendLine("      codec=${e.codec}, block=${e.blockSize}, samples/frame=${e.samplesPerFrame}, samples=${e.samples}, rel=0x${e.audioRel.toString(16)}, abs=0x${e.audioAbs.toString(16)}")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 4, 0, 8)
                }
                val label = TextView(this).apply {
                    text = "#${e.globalIndex} • ${e.rate} Hz • ${"%.2f".format(java.util.Locale.ROOT,e.duration)} s • ${SoundParser.codecName(e.codec)}${if(e.name.isNotBlank()) "\\n${e.name}" else ""}"
                    setTextColor(0xFFE0E6EF.toInt())
                    textSize = 12f
                    setPadding(4, 4, 4, 4)
                }
                row.addView(label)
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val adpcmGeometrySupported = (e.blockSize == 22 && e.samplesPerFrame == 32) ||
                    (e.blockSize == 38 && e.samplesPerFrame == 64)
                val supported = (e.codec == 0x10 && e.channels == 1) || (e.codec == 0x00 && e.channels in 1..2)
                val replaceSupported = (e.codec == 0x10 && e.channels == 1 && adpcmGeometrySupported) ||
                    (e.codec == 0x00 && e.channels in 1..2)
                val play = Button(this).apply {
                    text = "PLAY"
                    isEnabled = supported
                    setOnClickListener { previewEntry(e) }
                }
                val export = Button(this).apply {
                    text = "EXPORT WAV"
                    isEnabled = supported
                    setOnClickListener { prepareWavExport(e) }
                }
                val replace = Button(this).apply {
                    text = "REPLACE"
                    isEnabled = replaceSupported && bank.safeBodyLayout
                    setOnClickListener { chooseReplacementWav(e) }
                }
                val repack = Button(this).apply {
                    text = "REPACK"
                    isEnabled = (bank.format.startsWith("SND") || bank.format.startsWith("XWS")) &&
                        e.codec == 0x10 && e.channels == 1 && adpcmGeometrySupported && bank.safeBodyLayout
                    setOnClickListener { chooseRepackReplacementWav(e) }
                }
                actions.addView(play, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                actions.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                actions.addView(replace, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                actions.addView(repack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(actions)
                entryList.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            sb.appendLine()
            sb.appendLine("FIRST 128 BYTES")
            bankBytes.take(128).chunked(16).forEachIndexed { row, chunk ->
                sb.append("%04X  ".format(row*16))
                sb.appendLine(chunk.joinToString(" ") { "%02X".format(it.toInt() and 0xff) })
            }
            sb.appendLine()
            sb.appendLine("CURRENT LIMITS")
            sb.appendLine("• Playback and WAV export support mono MS-ADPCM (0x10) and mono/stereo PCM16 (0x00).")
            sb.appendLine("• Some streams can contain unsupported/corrupt block metadata; failed decode is rejected.")
            sb.appendLine("• WAV replacement supports mono MS-ADPCM and mono/stereo PCM16 when the bank layout passes the safety heuristic.")
            sb.appendLine("• Replacement requires PCM16 WAV with matching channel count, sample rate, frame count, and encoded output size. Supported source codecs: mono MS-ADPCM and mono/stereo PCM16.")
            sb.appendLine("• Embedded SND/XWS candidates inside DAT-like files are scanned and opened read-only. Saving an embedded-bank edit writes a separate SND/XWS file; it does not rebuild the original DAT.")
            sb.appendLine("• Reload Source reopens the originally selected file and discards unsaved in-memory changes. Exported files remain separate.")
            output.text = sb.toString()
            if (embeddedCandidateCount == 0) {
                embeddedButton.isEnabled = false
                embeddedButton.text = "NEXT EMBEDDED BANK"
            }
            status.text = "Parsed ${bank.entries.size} subsounds" + if (embeddedCandidateCount > 1) " • embedded bank ${embeddedCandidateIndex + 1}/$embeddedCandidateCount" else ""
        } catch (e: Exception) {
            status.text = "Could not parse this file"
            output.text = "${e.javaClass.simpleName}: ${e.message}\n\nNo file was modified."
            entryList.removeAllViews()
            loadedBytes = null
            embeddedButton.isEnabled = false
        }
    }



    private fun previewEntry(entry: SoundEntry) {
        try {
            stopPlayback()
            val bytes = loadedBytes ?: throw IllegalStateException("Open a sound bank first.")
            val wav = SoundParser.decodeToWav(bytes, entry)
            require(wav.size >= 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") {
                "Decoder did not produce a valid WAV."
            }
            val pcm = wav.copyOfRange(44, wav.size)
            val channelCount = if (entry.codec == 0x00) entry.channels else 1
            val outputMask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            stopRequested.set(false)
            status.text = "Preparing playback for sound #${entry.globalIndex}…"
            thread(name = "NGSE-AudioPreview") {
                var track: AudioTrack? = null
                try {
                    val minBuffer = AudioTrack.getMinBufferSize(
                        entry.rate, outputMask, AudioFormat.ENCODING_PCM_16BIT
                    )
                    require(minBuffer > 0) { "Android audio output does not support this format (code $minBuffer)." }
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setSampleRate(entry.rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(outputMask)
                            .build())
                        .setBufferSizeInBytes(maxOf(minBuffer, 8192))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    activeTrack = track
                    track.play()
                    var offset = 0
                    while (offset < pcm.size && !stopRequested.get()) {
                        val count = track.write(pcm, offset, minOf(8192, pcm.size - offset))
                        if (count < 0) throw IllegalStateException("AudioTrack write failed: $count")
                        if (count == 0) break
                        offset += count
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Playback failed: ${e.message}" }
                } finally {
                    try { track?.pause() } catch (_: Exception) {}
                    try { track?.flush() } catch (_: Exception) {}
                    try { track?.release() } catch (_: Exception) {}
                    if (activeTrack === track) activeTrack = null
                    runOnUiThread {
                        if (!stopRequested.get()) status.text = "Playback finished • sound #${entry.globalIndex}"
                        else status.text = "Playback stopped"
                    }
                }
            }
        } catch (e: Exception) {
            status.text = "Playback failed: ${e.message}"
        }
    }

    private fun stopPlayback() {
        stopRequested.set(true)
        try { activeTrack?.pause() } catch (_: Exception) {}
        try { activeTrack?.flush() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }


    private fun chooseRepackReplacementWav(entry: SoundEntry) {
        pendingReplaceEntry = entry
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Android document providers label WAV files inconsistently
            // (audio/wav, audio/x-wav, or application/octet-stream).
            // Let the user choose the file, then validate its WAV contents.
            type = "*/*"
        }, 1010)
    }

    private fun repackReplaceFromWav(uri: Uri) {
        try {
            val original = loadedBytes ?: throw IllegalStateException("Open a sound bank first.")
            val bank = SoundParser.parse(original)
            val entry = pendingReplaceEntry ?: throw IllegalStateException("No target sound selected.")
            val wav = contentResolver.openInputStream(uri)?.use { readLimited(it, 64L * 1024 * 1024) }
                ?: throw IllegalStateException("Could not read replacement WAV.")
            val updated = if (bank.format.startsWith("SND")) {
                SoundParser.replaceEntryWithSndRepack(original, bank, entry, wav)
            } else {
                SoundParser.replaceEntryWithXwsRepack(original, bank, entry, wav)
            }
            pendingBankOutput = updated
            val baseName = queryName(currentUri ?: uri).substringBeforeLast('.', "soundbank")
            pendingBankName = "${baseName}_repacked.${if (bank.format.startsWith("SND")) "SND" else "xws"}"
            status.text = "Repack passed structural checks. Choose a separate output file."
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, pendingBankName)
            }, 1004)
        } catch (e: Exception) {
            status.text = "Repack replacement rejected: ${e.message}"
        } finally {
            pendingReplaceEntry = null
        }
    }

    private fun chooseReplacementWav(entry: SoundEntry) {
        pendingReplaceEntry = entry
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Do not filter by a single WAV MIME type: Android providers vary.
            // The selected file is validated by the WAV parser after selection.
            type = "*/*"
        }, 1003)
    }

    private fun replaceFromWav(uri: Uri) {
        try {
            val original = loadedBytes ?: throw IllegalStateException("Open a sound bank first.")
            val bank = SoundParser.parse(original)
            val entry = pendingReplaceEntry ?: throw IllegalStateException("No target sound selected.")
            val wav = contentResolver.openInputStream(uri)?.use { readLimited(it, 64L * 1024 * 1024) }
                ?: throw IllegalStateException("Could not read replacement WAV.")
            val updated = SoundParser.replaceEntryWithWav(original, bank, entry, wav)
            pendingBankOutput = updated
            val baseName = queryName(currentUri ?: uri).substringBeforeLast('.', "soundbank")
            pendingBankName = "${baseName}_replaced.${if (bank.format.startsWith("SND")) "SND" else "xws"}"
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, pendingBankName)
            }, 1004)
        } catch (e: Exception) {
            status.text = "Replacement rejected: ${e.message}"
        } finally {
            pendingReplaceEntry = null
        }
    }

    private fun savePendingBank(uri: Uri) {
        val bytes = pendingBankOutput ?: return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output destination.")
            status.text = "Saved separate modified copy: $pendingBankName (${bytes.size} bytes). Original file was not changed."
            // Keep the modified copy active so additional replacements can be applied to it.
            loadedBytes = bytes
        } catch (e: Exception) {
            status.text = "Could not save modified bank: ${e.message}"
        } finally {
            pendingBankOutput = null
        }
    }

    private fun chooseBatchSndRepackZip() {
        val bytes = loadedBytes ?: run { status.text = "Open an SND bank first."; return }
        val bank = try { SoundParser.parse(bytes) } catch (e: Exception) {
            status.text = "Could not parse bank: ${e.message}"; return
        }
        if (!bank.format.startsWith("SND") && !bank.format.startsWith("XWS")) {
            status.text = "Variable-length batch repack requires an SND/XWS bank."
            return
        }
        if (!bank.safeBodyLayout) {
            status.text = "Repack disabled: body layout did not pass safety checks."
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }, 1011)
    }

    private fun batchSndRepackFromZip(uri: Uri) {
        try {
            val original = loadedBytes ?: throw IllegalStateException("Open an SND bank first.")
            val originalBank = SoundParser.parse(original)
            require(originalBank.format.startsWith("SND") || originalBank.format.startsWith("XWS")) {
                "Variable-length batch repack requires an SND/XWS bank."
            }
            require(originalBank.safeBodyLayout) { "Bank layout did not pass safety checks." }
            var updated = original.copyOf()
            var replaced = 0
            var skipped = 0
            var foundWavs = 0
            var archiveEntries = 0
            contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val ze = zip.nextEntry ?: break
                        archiveEntries++
                        require(archiveEntries <= 2000) { "ZIP has too many entries (limit 2000)." }
                        if (ze.isDirectory) { zip.closeEntry(); continue }
                        val leaf = ze.name.substringAfterLast('/')
                        val match = Regex("(?i)^sound_(\\d{1,6})(?:_[^/]*)?\\.wav$").matchEntire(leaf)
                        if (match == null) { zip.closeEntry(); continue }
                        foundWavs++
                        try {
                            val index = match.groupValues[1].toInt()
                            val currentBank = SoundParser.parse(updated)
                            val entry = currentBank.entries.firstOrNull { it.globalIndex == index }
                                ?: throw IllegalArgumentException("No sound index $index.")
                            require(entry.codec == 0x10 && entry.channels == 1) {
                                "Variable-length repack supports mono MS-ADPCM entries only."
                            }
                            val wav = readLimited(zip, 64L * 1024 * 1024)
                            updated = if (currentBank.format.startsWith("SND")) {
                                SoundParser.replaceEntryWithSndRepack(updated, currentBank, entry, wav)
                            } else {
                                SoundParser.replaceEntryWithXwsRepack(updated, currentBank, entry, wav)
                            }
                            replaced++
                        } catch (_: Exception) {
                            skipped++
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("Could not open ZIP.")
            require(foundWavs > 0) { "No sound_NNN.wav files found in ZIP." }
            require(replaced > 0) { "No WAV entries were successfully repacked." }
            pendingBankOutput = updated
            val baseName = (currentUri?.let { queryName(it) } ?: "soundbank").substringBeforeLast('.', "soundbank")
            pendingBankName = "${baseName}_batch_repacked.${if (originalBank.format.startsWith("SND")) "SND" else "xws"}"
            status.text = "SND batch repack: $replaced replaced, $skipped skipped. Choose output path."
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, pendingBankName)
            }, 1004)
        } catch (e: Exception) {
            status.text = "SND batch repack failed: ${e.message}"
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "Input exceeds the ${maxBytes / (1024 * 1024)} MiB size limit." }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun chooseBatchReplacementZip() {
        if (loadedBytes == null) { status.text = "Open a sound bank first."; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }, 1008)
    }

    private fun batchReplaceFromZip(uri: Uri) {
        try {
            val original = loadedBytes ?: throw IllegalStateException("Open a sound bank first.")
            val bank = SoundParser.parse(original)
            val entriesByIndex = bank.entries.associateBy { it.globalIndex }
            var updated = original.copyOf()
            var replaced = 0
            val skipped = mutableListOf<String>()
            var foundWavs = 0
            var archiveEntries = 0
            contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val ze = zip.nextEntry ?: break
                        archiveEntries++
                        require(archiveEntries <= 2000) { "ZIP has too many entries (limit 2000)." }
                        if (ze.isDirectory) { zip.closeEntry(); continue }
                        val leaf = ze.name.substringAfterLast('/')
                        val match = Regex("(?i)^sound_(\\d{1,6})(?:_[^/]*)?\\.wav$").matchEntire(leaf)
                        if (match == null) { zip.closeEntry(); continue }
                        foundWavs++
                        val idx = match.groupValues[1].toIntOrNull()
                        val entry = idx?.let { entriesByIndex[it] }
                        if (entry == null) {
                            skipped += "$leaf: no matching sound index"
                            zip.closeEntry()
                            continue
                        }
                        try {
                            val wav = readLimited(zip, 64L * 1024 * 1024)
                            val currentBank = SoundParser.parse(updated)
                            updated = SoundParser.replaceEntryWithWav(updated, currentBank, entry, wav)
                            replaced++
                        } catch (e: Exception) {
                            skipped += "$leaf: ${e.message ?: "rejected"}"
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("Could not open replacement ZIP.")
            require(foundWavs > 0) { "No sound_NNN.wav files were found in the ZIP." }
            require(replaced > 0) { "No replacements succeeded. ${skipped.take(4).joinToString("; ")}" }
            pendingBankOutput = updated
            val baseName = (currentUri?.let { queryName(it) } ?: "soundbank").substringBeforeLast('.', "soundbank")
            pendingBankName = "${baseName}_batch_replaced.${if (bank.format.startsWith("SND")) "SND" else "xws"}"
            status.text = "Batch replacement: $replaced replaced, ${skipped.size} skipped. Choose a new output file."
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, pendingBankName)
            }, 1004)
        } catch (e: Exception) {
            status.text = "Batch replacement failed: ${e.message}"
        }
    }

    private fun chooseSoundMapDestination() {
        val bytes = loadedBytes ?: run { status.text = "Open a bank first."; return }
        val name = currentUri?.let { queryName(it) } ?: "soundbank"
        val base = name.substringBeforeLast('.', "soundbank")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "${base}_sound_map.csv")
        }, 1007)
    }

    private fun exportSoundMap(uri: Uri) {
        val bytes = loadedBytes ?: return
        try {
            val bank = SoundParser.parse(bytes)
            fun csv(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
            val text = buildString {
                appendLine("index,record,sub_index,name,codec_id,codec,channels,sample_rate,samples,duration_seconds,block_size,samples_per_frame,audio_relative_offset,audio_absolute_offset,audio_size")
                for (e in bank.entries) {
                    appendLine(listOf(
                        e.globalIndex.toString(), e.soundRecord.toString(), e.subIndex.toString(),
                        csv(e.name), e.codec.toString(), csv(SoundParser.codecName(e.codec)),
                        e.channels.toString(), e.rate.toString(), e.samples.toString(),
                        "%.6f".format(java.util.Locale.ROOT, e.duration),
                        e.blockSize.toString(), e.samplesPerFrame.toString(),
                        e.audioRel.toString(), e.audioAbs.toString(), e.audioSize.toString()
                    ).joinToString(","))
                }
            }
            contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Could not open CSV destination.")
            status.text = "Exported sound map: ${bank.entries.size} rows."
        } catch (e: Exception) {
            status.text = "Sound map export failed: ${e.message}"
        }
    }

    private fun chooseBatchWavDestination() {
        val bank = loadedBytes ?: run { status.text = "Open a bank first."; return }
        val parsed = try { SoundParser.parse(bank) } catch (e: Exception) {
            status.text = "Cannot batch export: ${e.message}"
            return
        }
        val supported = parsed.entries.filter {
            (it.codec == 0x10 && it.channels == 1) ||
                (it.codec == 0x00 && it.channels in 1..2)
        }
        if (supported.isEmpty()) {
            status.text = "No supported entries found for WAV export."
            return
        }
        val sourceName = currentUri?.let { queryName(it) } ?: "soundbank"
        val base = sourceName.substringBeforeLast('.', "soundbank")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "${base}_wav_export.zip")
        }, 1006)
    }

    private fun exportAllWavs(uri: Uri) {
        val bankBytes = loadedBytes ?: return
        try {
            val bank = SoundParser.parse(bankBytes)
            val supported = bank.entries.filter {
                (it.codec == 0x10 && it.channels == 1) ||
                    (it.codec == 0x00 && it.channels in 1..2)
            }
            var exported = 0
            var failed = 0
            contentResolver.openOutputStream(uri, "w")?.use { raw ->
                ZipOutputStream(raw).use { zip ->
                    for (entry in supported) {
                        try {
                            val wav = SoundParser.decodeToWav(bankBytes, entry)
                            val name = "sound_${entry.globalIndex.toString().padStart(3,'0')}" +
                                if (entry.name.isNotBlank()) "_${entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)}.wav" else ".wav"
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(wav)
                            zip.closeEntry()
                            exported++
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                }
            } ?: throw IllegalStateException("Could not open ZIP destination.")
            status.text = "Batch WAV export complete: $exported exported, $failed skipped."
        } catch (e: Exception) {
            status.text = "Batch WAV export failed: ${e.message}"
        }
    }

    private fun chooseBankCopyDestination() {
        val bytes = loadedBytes ?: run { status.text = "Open a bank first."; return }
        val sourceName = currentUri?.let { queryName(it) } ?: "soundbank"
        val base = sourceName.substringBeforeLast('.', "soundbank")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        pendingRawName = "${base}_bank_copy.${if (bytes.size >= 4 && String(bytes,0,4,Charsets.US_ASCII) == "SND\\u0000") "SND" else "xws"}"
        pendingRawBank = bytes.copyOf()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, pendingRawName)
        }, 1005)
    }

    private fun saveRawBankCopy(uri: Uri) {
        val bytes = pendingRawBank ?: return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open destination.")
            status.text = "Exported bank copy: $pendingRawName (${bytes.size} bytes)"
        } catch (e: Exception) {
            status.text = "Could not export bank copy: ${e.message}"
        } finally {
            pendingRawBank = null
        }
    }

    private fun prepareWavExport(entry: SoundEntry) {
        try {
            val bytes = loadedBytes ?: throw IllegalStateException("Open a sound bank first.")
            pendingWav = SoundParser.decodeToWav(bytes, entry)
            pendingWavName = "NGSE_${entry.globalIndex.toString().padStart(3,'0')}.wav"
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, pendingWavName)
            }, 1002)
        } catch (e: Exception) {
            status.text = "WAV export failed: ${e.message}"
        }
    }

    private fun savePendingWav(uri: Uri) {
        val wav = pendingWav ?: return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(wav) }
                ?: throw IllegalStateException("Could not open destination for writing.")
            status.text = "Exported $pendingWavName (${wav.size} bytes)"
        } catch (e: Exception) {
            status.text = "Could not save WAV: ${e.message}"
        } finally {
            pendingWav = null
        }
    }

    private fun queryName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else uri.lastPathSegment ?: "unknown"
            } else uri.lastPathSegment ?: "unknown"
        } ?: (uri.lastPathSegment ?: "unknown")

    private object LocaleHolder { val ROOT = java.util.Locale.ROOT }
}
