package com.kosh.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.NotFoundException;
import com.kosh.config.AppProperties;
import com.kosh.storage.FileStorage.FileType;

/**
 * Upload storage (CLAUDE.md > Security): magic-byte allowlist, 5 MB, images that decode and are not too big in pixels,
 * random names, no path tricks, tidy deletes.
 */
public class FileStorageTest {

	static final byte[] PNG = image("png", 1, 1);

	static final byte[] JPEG = image("jpg", 1, 1);

	static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);

	private static final Set<FileType> IMAGES = EnumSet.of(FileType.PNG, FileType.JPEG);

	@TempDir
	Path root;

	private FileStorage storage() {
		return new FileStorage(new AppProperties("Kosh", ZoneId.of("Asia/Kolkata"), root, false,
				"kpWn1TbE2tBO1DnwRX28s8j1oWaO4LLq8bFLisaR7J4=", "http://localhost:8080",
				new AppProperties.Mail(AppProperties.Transport.LOG, "no-reply@kosh.test")));
	}

	@Test
	void anAllowedFileIsStoredUnderARandomNameWithTheExtensionOfItsRealType() throws IOException {
		FileStorage storage = storage();
		// The browser's name and content type are ignored: this "logo.gif" is a PNG.
		FileStorage.StoredFile stored = storage.store("company",
				new MockMultipartFile("file", "logo.gif", "image/gif", PNG), IMAGES);

		assertThat(stored.type()).isEqualTo(FileType.PNG);
		assertThat(stored.path()).matches("company/[0-9a-f-]{36}\\.png");
		assertThat(Files.readAllBytes(root.resolve(stored.path()))).isEqualTo(PNG);
		assertThat(storage.load(stored.path()).getContentAsByteArray()).isEqualTo(PNG);
		assertThat(FileStorage.typeOf(stored.path())).isEqualTo(FileType.PNG);

		FileStorage.StoredFile jpeg = storage.store("company", new MockMultipartFile("file", "x.png", "image/png", JPEG),
				IMAGES);
		assertThat(jpeg.path()).endsWith(".jpg").isNotEqualTo(stored.path());
		assertThat(FileStorage.typeOf(jpeg.path()).contentType()).isEqualTo("image/jpeg");

		FileStorage.StoredFile pdf = storage.store("documents", new MockMultipartFile("file", "offer.pdf", null, PDF),
				EnumSet.allOf(FileType.class));
		assertThat(pdf.path()).startsWith("documents/").endsWith(".pdf");
	}

	@Test
	void aFileOfAnotherTypeIsRefusedWhateverItsName() {
		FileStorage storage = storage();
		byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
			.getBytes(StandardCharsets.UTF_8);
		for (byte[] bytes : new byte[][] { gif, svg, PDF, Arrays.copyOf(PNG, 4) }) {
			assertThatThrownBy(() -> storage.store("company", new MockMultipartFile("file", "logo.png", "image/png", bytes),
					IMAGES))
				.isInstanceOfSatisfying(BusinessRuleException.class, rule -> assertThat(rule.getField()).isEqualTo("file"))
				.hasMessage("Upload a JPG or PNG file.");
		}
		assertThatThrownBy(() -> storage.store("x", new MockMultipartFile("file", "a.png", null, PNG), Set.of(FileType.PDF)))
			.hasMessage("Upload a PDF file.");
		assertThatThrownBy(() -> storage.store("x", new MockMultipartFile("file", "a.txt", null, gif),
				EnumSet.allOf(FileType.class)))
			.hasMessage("Upload a PDF, JPG or PNG file.");
		assertThat(root).isEmptyDirectory();
	}

	@Test
	void anImageThatDoesNotDecodeIsRefused() {
		FileStorage storage = storage();
		byte[] onlyTheSignature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R' };
		byte[] png = image("png", 40, 30);
		byte[] truncatedHeader = Arrays.copyOf(png, 20);
		// The whole header (signature 8 + IHDR 25 bytes) is there, the pixel data is cut short.
		byte[] truncatedPixels = Arrays.copyOf(png, 33 + (png.length - 33) / 2);
		byte[] onlyAJpegHeader = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };
		for (byte[] bytes : new byte[][] { onlyTheSignature, truncatedHeader, truncatedPixels, onlyAJpegHeader }) {
			assertThatThrownBy(() -> storage.store("company", new MockMultipartFile("file", "logo.png", null, bytes), IMAGES))
				.isInstanceOfSatisfying(BusinessRuleException.class, rule -> assertThat(rule.getField()).isEqualTo("file"))
				.hasMessage("The image can’t be read. Save it again as PNG or JPG and upload it.");
		}
		assertThat(root).isEmptyDirectory();
	}

	@Test
	void anImageOverSixteenMegapixelsIsRefusedFromItsHeaderBeforeDecoding() {
		FileStorage storage = storage();
		assertThat(storage.store("company", new MockMultipartFile("file", "a.png", null, image("png", 4000, 4000)), IMAGES)
			.type()).isEqualTo(FileType.PNG);
		// A tiny file whose header claims 30000 × 30000: decoding it would need gigabytes.
		byte[] bomb = withSize(PNG, 30_000, 30_000);
		assertThatThrownBy(() -> storage.store("company", new MockMultipartFile("file", "bomb.png", null, bomb), IMAGES))
			.isInstanceOfSatisfying(BusinessRuleException.class, rule -> assertThat(rule.getField()).isEqualTo("file"))
			.hasMessage("The image is larger than 16 megapixels (4000 × 4000). Make it smaller and upload it again.");
		assertThatThrownBy(() -> storage.store("company",
				new MockMultipartFile("file", "wide.png", null, withSize(PNG, 4001, 4000)), IMAGES))
			.hasMessageStartingWith("The image is larger than 16 megapixels");
	}

	@Test
	void anEmptyOrMissingFileIsRefused() {
		FileStorage storage = storage();
		assertThatThrownBy(() -> storage.store("company", null, IMAGES)).hasMessage("Choose a file to upload.");
		assertThatThrownBy(() -> storage.store("company", new MockMultipartFile("file", "", null, new byte[0]), IMAGES))
			.hasMessage("Choose a file to upload.");
	}

	@Test
	void filesAreCappedAtFiveMegabytes() {
		FileStorage storage = storage();
		byte[] limit = Arrays.copyOf(PNG, 5 * 1024 * 1024);
		assertThat(storage.store("company", new MockMultipartFile("file", "big.png", null, limit), IMAGES).type())
			.isEqualTo(FileType.PNG);
		byte[] over = Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1);
		assertThatThrownBy(() -> storage.store("company", new MockMultipartFile("file", "big.png", null, over), IMAGES))
			.hasMessage("The file is larger than 5 MB. Choose a smaller one.");
	}

	@Test
	void onlyPathsThisClassHandsOutCanBeRead() throws IOException {
		FileStorage storage = storage();
		Files.writeString(root.resolve("secret.png"), "not stored through FileStorage");
		for (String path : new String[] { "secret.png", "../secret.png", "company/../secret.png", "/etc/passwd",
				"company/logo.png",
				"Company/123e4567-e89b-12d3-a456-426614174000.png", "", null }) {
			assertThatThrownBy(() -> storage.load(path)).as(path).isInstanceOf(NotFoundException.class);
		}
		assertThatThrownBy(() -> storage.load("company/123e4567-e89b-12d3-a456-426614174000.png"))
			.as("well-formed but missing")
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void deletingRemovesTheFileAndToleratesOneThatIsGone() {
		FileStorage storage = storage();
		String path = storage.store("company", new MockMultipartFile("file", "a.png", null, PNG), IMAGES).path();
		storage.delete(path);
		assertThat(root.resolve(path)).doesNotExist();
		storage.delete(path);
		storage.delete("../not-ours.png");
	}

	@Test
	void afterATransactionTheReplacedFileGoesOnCommitAndTheNewOneOnRollback() {
		FileStorage storage = storage();
		String old = storage.store("company", new MockMultipartFile("file", "a.png", null, PNG), IMAGES).path();
		String replacement = storage.store("company", new MockMultipartFile("file", "b.png", null, PNG), IMAGES).path();

		complete(TransactionSynchronization.STATUS_ROLLED_BACK, () -> storage.afterTransaction(old, replacement));
		assertThat(root.resolve(old)).exists();
		assertThat(root.resolve(replacement)).doesNotExist();

		complete(TransactionSynchronization.STATUS_COMMITTED, () -> storage.afterTransaction(old, null));
		assertThat(root.resolve(old)).doesNotExist();
	}

	@Test
	void withoutATransactionNothingIsDeleted() {
		FileStorage storage = storage();
		String path = storage.store("company", new MockMultipartFile("file", "a.png", null, PNG), IMAGES).path();
		storage.afterTransaction(path, path);
		assertThat(root.resolve(path)).exists();
	}

	/** A real image of {@code width} × {@code height} black pixels, as {@code "png"} or {@code "jpg"} bytes. */
	public static byte[] image(String format, int width, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
		}
		catch (IOException failed) {
			throw new UncheckedIOException(failed);
		}
		return out.toByteArray();
	}

	/** {@code png} with the size in its IHDR header (and that chunk's CRC) rewritten; the pixel data stays as it was. */
	private static byte[] withSize(byte[] png, int width, int height) {
		byte[] copy = png.clone();
		ByteBuffer bytes = ByteBuffer.wrap(copy);
		bytes.putInt(16, width).putInt(20, height);
		CRC32 crc = new CRC32();
		crc.update(copy, 12, 17);
		bytes.putInt(29, (int) crc.getValue());
		return copy;
	}

	/** Runs {@code work} as if inside a transaction that then ends with {@code status}. */
	private static void complete(int status, Runnable work) {
		TransactionSynchronizationManager.initSynchronization();
		try {
			work.run();
			TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

}
