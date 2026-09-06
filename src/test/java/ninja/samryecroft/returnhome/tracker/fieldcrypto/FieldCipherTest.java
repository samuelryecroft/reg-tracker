package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What these assert is not "AES works" - it is the four properties the design actually leans on:
 * that the same plaintext never produces the same ciphertext, that ciphertext cannot be moved
 * between organisations or between columns, and that every failure is a refusal rather than a
 * fallback.
 */
@ExtendWith(MockitoExtension.class)
class FieldCipherTest {

    private static final long ORG = 7L;
    private static final long OTHER_ORG = 8L;

    private FieldKeyService keyService;
    private FieldCipher cipher;

    @BeforeEach
    void setUp() {
        keyService = mock(FieldKeyService.class);
        // lenient, per stub, for two different reasons - not a blanket silence:
        // ORG is used by every test that actually ciphers, but leavesNullAlone never reaches the key
        // at all, and failsClosedWhenTheKeyIsUnavailable re-stubs this call to throw.
        lenient().when(keyService.dataKeyFor(ORG)).thenReturn(key('a'));
        // OTHER_ORG exists only for the two cross-organisation tests; the other nine never ask for a
        // second key. It is stubbed here so those two read as a comparison rather than as setup.
        lenient().when(keyService.dataKeyFor(OTHER_ORG)).thenReturn(key('b'));
        cipher = new FieldCipher(keyService);
    }

    private SecretKey key(char fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return new SecretKeySpec(bytes, "AES");
    }

    @Test
    void roundTripsAValue() {
        String plaintext = "Went missing after an argument about school.";

        String encrypted = cipher.encrypt(ORG, "InterviewReport.whatMadeYouGoMissing", plaintext);

        assertThat(encrypted).startsWith(FieldCipher.PREFIX);
        assertThat(new String(java.util.Base64.getDecoder()
                .decode(encrypted.split(":")[2]), StandardCharsets.UTF_8))
                .as("the stored bytes must not contain the plaintext")
                .doesNotContain("school");
        assertThat(cipher.decrypt(ORG, "InterviewReport.whatMadeYouGoMissing", encrypted))
                .isEqualTo(plaintext);
    }

    /**
     * Randomized, not deterministic. Were this to fail, equal ciphertexts would group children by
     * shared values - a birthday, a common first name - which is most of what the encryption is for.
     */
    @Test
    void encryptsTheSameValueDifferentlyEveryTime() {
        String first = cipher.encrypt(ORG, "Child.firstName", "Jamie");
        String second = cipher.encrypt(ORG, "Child.firstName", "Jamie");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(ORG, "Child.firstName", first)).isEqualTo("Jamie");
        assertThat(cipher.decrypt(ORG, "Child.firstName", second)).isEqualTo("Jamie");
    }

    /** Per-organisation isolation: another organisation's key does not open this value. */
    @Test
    void refusesToDecryptAnotherOrganisationsValue() {
        String encrypted = cipher.encrypt(ORG, "Child.lastName", "Okafor");

        assertThatThrownBy(() -> cipher.decrypt(OTHER_ORG, "Child.lastName", encrypted))
                .isInstanceOf(FieldCryptoException.class)
                .hasMessageContaining("has not been released");
    }

    /**
     * The organisation is bound into the tag as well as choosing the key, so even sharing a key
     * does not let a value be read as another organisation's. This is the property that makes a
     * relabelled row fail rather than decrypt.
     */
    @Test
    void refusesAValueRelabelledAsAnotherOrganisation() {
        when(keyService.dataKeyFor(OTHER_ORG)).thenReturn(key('a'));
        String encrypted = cipher.encrypt(ORG, "Child.lastName", "Okafor");

        assertThatThrownBy(() -> cipher.decrypt(OTHER_ORG, "Child.lastName", encrypted))
                .isInstanceOf(FieldCryptoException.class);
    }

    /** Nor can ciphertext be moved between columns - the field is authenticated too. */
    @Test
    void refusesAValueMovedToADifferentColumn() {
        String encrypted = cipher.encrypt(ORG, "Child.firstName", "Jamie");

        assertThatThrownBy(() -> cipher.decrypt(ORG, "Child.lastName", encrypted))
                .isInstanceOf(FieldCryptoException.class);
    }

    @Test
    void refusesTamperedCiphertext() {
        String encrypted = cipher.encrypt(ORG, "Child.firstName", "Jamie");
        String tampered = encrypted.substring(0, encrypted.length() - 5) + "AAAAA";

        assertThatThrownBy(() -> cipher.decrypt(ORG, "Child.firstName", tampered))
                .isInstanceOf(FieldCryptoException.class);
    }

    /**
     * Plaintext sitting in a column that should hold ciphertext is the failure most worth shouting
     * about, because returning it quietly is exactly how nobody finds out.
     */
    @Test
    void refusesPlaintextFoundInAnEncryptedColumn() {
        assertThatThrownBy(() -> cipher.decrypt(ORG, "Child.firstName", "Jamie"))
                .isInstanceOf(FieldCryptoException.class)
                .hasMessageContaining("not in the expected");
    }

    /** Absent stays absent: encrypting null would make a blank column distinguishable from a set one. */
    @Test
    void leavesNullAlone() {
        assertThat(cipher.encrypt(ORG, "Child.localCaseReference", null)).isNull();
        assertThat(cipher.decrypt(ORG, "Child.localCaseReference", null)).isNull();
    }

    @Test
    void roundTripsAnEmptyStringAsDistinctFromNull() {
        String encrypted = cipher.encrypt(ORG, "Child.localCaseReference", "");

        assertThat(encrypted).isNotNull();
        assertThat(cipher.decrypt(ORG, "Child.localCaseReference", encrypted)).isEmpty();
    }

    /** Fail closed: a key we cannot get stops the write; it never lets it through in the clear. */
    @Test
    void failsClosedWhenTheKeyIsUnavailable() {
        when(keyService.dataKeyFor(ORG)).thenThrow(new FieldCryptoException("vault unreachable"));

        assertThatThrownBy(() -> cipher.encrypt(ORG, "Child.firstName", "Jamie"))
                .isInstanceOf(FieldCryptoException.class);
    }
}
