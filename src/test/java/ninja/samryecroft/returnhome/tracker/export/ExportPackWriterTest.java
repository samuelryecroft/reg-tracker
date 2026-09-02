package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pack itself. These assertions are about the artefact that leaves the building, so they check
 * what a recipient actually receives rather than what the code intended to produce.
 */
class ExportPackWriterTest {

    private static final byte[] ORIGINAL_REPORT = "PK the report exactly as issued".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private final ExportPackWriter writer = new ExportPackWriter(new CaseFileNarrativeWriter());

    private ExportPackWriter.PackRequest request(String passphrase, List<ExportManifest.ManifestEntry> excluded) {
        ExportManifest manifest = new ExportManifest("CASE-001", "All interviews",
                List.of(ExportManifest.ManifestEntry.included(1182L, "Interview #1182 — 3 Aug 2026", true)),
                excluded, List.of(), false, null);
        return new ExportPackWriter.PackRequest("CASE-001", manifest, List.of(),
                List.of(new ExportPackWriter.AttachedReport(1182L, "reports/interview-1182-report.docx", ORIGINAL_REPORT)),
                ExportPurpose.REGULATORY_INSPECTION, "OFSTED-2026-11", "orgadmin",
                LocalDateTime.of(2026, 9, 2, 9, 41), passphrase);
    }

    @Test
    void attachesTheReportExactlyAsIssued() throws IOException {
        ExportPack pack = writer.write(request("", List.of()));

        // The single most consequential property of the pack: a re-rendered report would pick up
        // today's template and branding and silently differ from the document actually sent to the
        // placing authority - an artefact that never existed, presented as evidence.
        assertThat(entry(pack, "reports/interview-1182-report.docx")).isEqualTo(ORIGINAL_REPORT);
    }

    @Test
    void isEncryptedByDefaultWithAGeneratedPassphrase() throws IOException {
        // null passphrase means "the default", which is on. A protection you must remember to switch
        // on is one that does not change behaviour.
        ExportPack pack = writer.write(request(null, List.of()));

        assertThat(pack.isProtected()).isTrue();
        assertThat(pack.passphrase()).hasSizeGreaterThanOrEqualTo(20);

        Path file = tempDir.resolve("pack.zip");
        Files.write(file, pack.content());
        assertThatThrownBy(() -> new ZipFile(file.toFile()).extractAll(tempDir.resolve("out").toString()))
                .isInstanceOf(ZipException.class);

        ZipFile withPassphrase = new ZipFile(file.toFile(), pack.passphrase().toCharArray());
        withPassphrase.extractAll(tempDir.resolve("ok").toString());
        assertThat(new File(tempDir.resolve("ok").toFile(), "case-file.pdf")).exists();
    }

    @Test
    void protectionCanBeTurnedOffDeliberately() throws IOException {
        // Kept removable on purpose: a locked archive a recipient cannot open does not produce
        // security, it produces someone re-sending it unlocked from their own laptop, outside this
        // audit trail entirely.
        ExportPack pack = writer.write(request("", List.of()));

        assertThat(pack.isProtected()).isFalse();
        assertThat(entry(pack, "case-file.pdf")).isNotEmpty();
    }

    @Test
    void theCoverSheetStatesWhatIsMissingAndWhy() throws IOException {
        ExportPack pack = writer.write(request("", List.of(ExportManifest.ManifestEntry.excluded(
                1191L, "Interview #1191 — 14 Aug 2026", "No approved report - the report is still a draft"))));

        String coverSheet = pdfText(entry(pack, "case-file.pdf"));
        assertThat(coverSheet).contains("What is not in this pack");
        assertThat(coverSheet).contains("Interview #1191");
        assertThat(coverSheet).contains("still a draft");
        // An exclusion without its reason is the thing this design forbids - a bare gap reads as
        // concealment, a stated one reads as completeness.
    }

    @Test
    void theCoverSheetCarriesScopePurposeAndProvenance() throws IOException {
        ExportPack pack = writer.write(request("", List.of()));

        String coverSheet = pdfText(entry(pack, "case-file.pdf"));
        assertThat(coverSheet).contains("CASE-001");
        assertThat(coverSheet).contains("Ofsted or regulatory inspection");
        assertThat(coverSheet).contains("OFSTED-2026-11");
        assertThat(coverSheet).contains("orgadmin");
        assertThat(coverSheet).contains("2 September 2026");
        // Per-document checksum, so a recipient can verify the attachment they hold is the one this
        // pack shipped. The pack's own SHA-256 cannot live inside itself; it goes in the audit row.
        assertThat(coverSheet).contains("SHA-256 " + ExportPackWriter.sha256(ORIGINAL_REPORT));
    }

    @Test
    void aPartialPackSaysSoOnItsFace() throws IOException {
        ExportManifest partial = new ExportManifest("CASE-001", "All interviews", List.of(), List.of(), List.of(),
                true, "This pack is partial. It contains the 3 interview(s) held by Beacon.");
        ExportPack pack = writer.write(new ExportPackWriter.PackRequest("CASE-001", partial, List.of(), List.of(),
                ExportPurpose.LEGAL_PROCEEDINGS, null, "coordinator", LocalDateTime.now(), ""));

        // Without this an inspector reads a supplier's partial file as the child's whole history.
        assertThat(pdfText(entry(pack, "case-file.pdf"))).contains("This pack is partial");
    }

    @Test
    void theChecksumIsOverTheBytesTheRecipientReceives() {
        ExportPack pack = writer.write(request("", List.of()));

        assertThat(pack.checksum()).isEqualTo(ExportPackWriter.sha256(pack.content()));
        assertThat(pack.checksum()).hasSize(64);
    }

    @Test
    void theFilenameCarriesTheCaseReferenceAndNotTheChildsName() {
        ExportPack pack = writer.write(request("", List.of()));

        // This filename is what appears in an inbox, a shared drive, and a forwarded email.
        assertThat(pack.filename()).isEqualTo("case-file-CASE-001-20260902-0941.zip");
    }

    private byte[] entry(ExportPack pack, String name) throws IOException {
        char[] passphrase = pack.isProtected() ? pack.passphrase().toCharArray() : null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(pack.content()), passphrase)) {
            LocalFileHeader header;
            while ((header = in.getNextEntry()) != null) {
                if (header.getFileName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        throw new AssertionError("No entry " + name + " in the pack");
    }

    private String pdfText(byte[] pdf) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }
}
