package ninja.samryecroft.returnhome.tracker.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import org.springframework.stereotype.Component;

/**
 * Assembles the deliverable: a cover sheet, a narrative of the case history, and the original report
 * documents, in one archive.
 *
 * <p>The pack is the part of this feature most likely to be under-designed, because it is not a
 * screen - but it is the artefact that leaves the building, gets filed, and gets re-sent. Two rules
 * govern it:
 *
 * <ul>
 *   <li><strong>Attachments are copied, never re-rendered.</strong> The bytes handed in are the bytes
 *       written. Re-rendering a report would pick up today's template and today's branding and
 *       silently differ from the document actually sent to the placing authority - an artefact that
 *       never existed, presented as evidence.</li>
 *   <li><strong>The cover sheet states exclusions as prominently as contents.</strong> A pack whose
 *       own face says what is missing, and why, is evidence of completeness; one that just omits is
 *       indistinguishable from concealment.</li>
 * </ul>
 *
 * <p>Encryption is on by default. A passphrase that must be turned off is the only version that
 * changes behaviour - but it stays removable, because a locked archive a recipient cannot open does
 * not produce security, it produces someone re-sending it unlocked from their own laptop, outside
 * the product and outside this audit trail.
 */
@Component
public class ExportPackWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final String PASSPHRASE_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PASSPHRASE_LENGTH = 20;

    private final CaseFileNarrativeWriter narrativeWriter;
    private final SecureRandom secureRandom = new SecureRandom();

    public ExportPackWriter(CaseFileNarrativeWriter narrativeWriter) {
        this.narrativeWriter = narrativeWriter;
    }

    /**
     * @param passphrase null to generate one (the default), blank to produce an unprotected archive
     *                   because the operator deliberately turned protection off
     */
    public record PackRequest(String childReference, ExportManifest manifest,
            List<AuditHistorySection> history, List<AttachedReport> attachments,
            ExportPurpose purpose, String reference, String generatedBy,
            LocalDateTime generatedAt, String passphrase) {
    }

    /** @param content the report exactly as issued - this class must not alter it */
    public record AttachedReport(Long interviewId, String entryName, byte[] content) {
    }

    public ExportPack write(PackRequest request) {
        String passphrase = resolvePassphrase(request.passphrase());
        byte[] narrative = narrativeWriter.write(request);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = passphrase == null
                ? new ZipOutputStream(archive)
                : new ZipOutputStream(archive, passphrase.toCharArray())) {

            writeEntry(zip, "case-file.pdf", narrative, passphrase != null);
            for (AttachedReport attachment : request.attachments()) {
                // Written verbatim. Nothing in this loop may transform the bytes.
                writeEntry(zip, attachment.entryName(), attachment.content(), passphrase != null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to assemble the export pack", e);
        }

        byte[] content = archive.toByteArray();
        return new ExportPack(filenameFor(request), content, sha256(content), passphrase);
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] content, boolean encrypted) throws IOException {
        ZipParameters parameters = new ZipParameters();
        parameters.setFileNameInZip(name);
        parameters.setCompressionMethod(CompressionMethod.DEFLATE);
        if (encrypted) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }
        zip.putNextEntry(parameters);
        zip.write(content);
        zip.closeEntry();
    }

    /**
     * Null in means "on by default" - generate one. Blank in means the operator turned it off and
     * that choice is honoured, since the alternative drives the data into channels we never see.
     */
    private String resolvePassphrase(String requested) {
        if (requested == null) {
            StringBuilder generated = new StringBuilder(PASSPHRASE_LENGTH);
            for (int i = 0; i < PASSPHRASE_LENGTH; i++) {
                generated.append(PASSPHRASE_ALPHABET.charAt(secureRandom.nextInt(PASSPHRASE_ALPHABET.length())));
            }
            return generated.toString();
        }
        return requested.isBlank() ? null : requested;
    }

    /**
     * Deliberately carries the case reference rather than the child's name: this filename is what
     * appears in an inbox, a shared drive and a forwarded email.
     */
    private String filenameFor(PackRequest request) {
        String reference = request.childReference().replaceAll("[^A-Za-z0-9._-]", "-");
        return "case-file-" + reference + "-" + request.generatedAt().format(FILE_STAMP) + ".zip";
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and always present", e);
        }
    }

    static String formatStamp(LocalDateTime at) {
        return at.format(STAMP);
    }
}
