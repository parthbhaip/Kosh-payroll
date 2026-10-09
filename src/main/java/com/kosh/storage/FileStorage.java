package com.kosh.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.kosh.common.BusinessRuleException;
import com.kosh.common.NotFoundException;
import com.kosh.config.AppProperties;

/**
 * Uploaded files under {@code app.upload-dir}, outside the classpath (CLAUDE.md > Security). A file is accepted only
 * when its first bytes are a PDF, JPEG or PNG signature allowed by the caller and it is at most {@value #MAX_MB} MB; a
 * JPEG or PNG must also decode and have at most 16 megapixels. The name the browser sent is ignored and a random one is
 * used. Callers keep the returned path ({@code folder/uuid.ext})
 * and serve the file through an authorised controller, never as a static resource.
 */
@Component
public class FileStorage {

	static final int MAX_MB = 5;

	public static final long MAX_BYTES = MAX_MB * 1024L * 1024L;

	/** 4000 × 4000: room for a 12 MP phone photo, while decoding one stays near 64 MB. */
	static final long MAX_IMAGE_PIXELS = 4000L * 4000L;

	/** Accepted kinds of file, recognised by their magic bytes. */
	public enum FileType {

		PDF("PDF", "application/pdf", "pdf", new byte[] { '%', 'P', 'D', 'F', '-' }),
		JPEG("JPG", "image/jpeg", "jpg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }),
		PNG("PNG", "image/png", "png", new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });

		private final String label;

		private final String contentType;

		private final String extension;

		private final byte[] magic;

		FileType(String label, String contentType, String extension, byte[] magic) {
			this.label = label;
			this.contentType = contentType;
			this.extension = extension;
			this.magic = magic;
		}

		public String label() {
			return label;
		}

		public String contentType() {
			return contentType;
		}

		/** The type whose signature starts {@code bytes}, if any. */
		static Optional<FileType> sniff(byte[] bytes) {
			return Arrays.stream(values())
				.filter(type -> bytes.length >= type.magic.length
						&& Arrays.equals(bytes, 0, type.magic.length, type.magic, 0, type.magic.length))
				.findFirst();
		}

	}

	/** Where a stored file lives ({@code company/9b2c….png}) and what it is. */
	public record StoredFile(String path, FileType type) {

	}

	/** Paths this class hands out; anything else is refused before it touches the disk. */
	private static final Pattern STORED_PATH = Pattern.compile("[a-z0-9-]+/[0-9a-f-]{36}\\.(pdf|jpg|png)");

	private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

	private final Path root;

	public FileStorage(AppProperties app) {
		this.root = app.uploadDir().toAbsolutePath().normalize();
	}

	/**
	 * Saves the upload under {@code folder} with a random name.
	 * @param folder lower-case letters, digits and hyphens, e.g. {@code company}
	 * @throws BusinessRuleException (field {@code file}) for a missing, empty, oversized or wrongly typed file, or an
	 * image that does not decode or has too many pixels
	 */
	public StoredFile store(String folder, MultipartFile file, Set<FileType> allowed) {
		if (file == null || file.isEmpty()) {
			throw new BusinessRuleException("file", "Choose a file to upload.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw new BusinessRuleException("file", "The file is larger than " + MAX_MB + " MB. Choose a smaller one.");
		}
		try {
			byte[] bytes = file.getBytes();
			FileType type = FileType.sniff(bytes)
				.filter(allowed::contains)
				.orElseThrow(() -> new BusinessRuleException("file", "Upload a " + labels(allowed) + " file."));
			if (type != FileType.PDF) {
				requireSaneImage(bytes, type);
			}
			String path = folder + "/" + UUID.randomUUID() + "." + type.extension;
			Path target = resolve(path);
			Files.createDirectories(target.getParent());
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
			return new StoredFile(path, type);
		}
		catch (IOException failed) {
			throw new UncheckedIOException("Could not store an upload in " + folder, failed);
		}
	}

	/** The stored file, for streaming to an authorised user. @throws NotFoundException when it is not there */
	public Resource load(String path) {
		Path file = resolve(path);
		if (!Files.isRegularFile(file)) {
			throw new NotFoundException("No stored file " + path);
		}
		return new FileSystemResource(file);
	}

	/** The type of a path this class returned. */
	public static FileType typeOf(String path) {
		String extension = path.substring(path.lastIndexOf('.') + 1);
		return Arrays.stream(FileType.values())
			.filter(type -> type.extension.equals(extension))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Not a stored file path"));
	}

	/** Deletes a stored file; a file that is already gone is fine. Failures are logged, not thrown. */
	public void delete(String path) {
		try {
			Files.deleteIfExists(resolve(path));
		}
		catch (IOException | NotFoundException failed) {
			log.warn("Could not delete stored file {}: {}", path, failed.getClass().getSimpleName());
		}
	}

	/**
	 * Tidies files up once the current transaction ends: on commit deletes {@code discardOnCommit} (the file a change
	 * replaced), on rollback deletes {@code discardOnRollback} (the file the change had just stored). Either may be null.
	 * Without a transaction nothing is deleted.
	 */
	public void afterTransaction(String discardOnCommit, String discardOnRollback) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				String discard = (status == STATUS_COMMITTED) ? discardOnCommit : discardOnRollback;
				if (discard != null) {
					delete(discard);
				}
			}
		});
	}

	/**
	 * A PNG or JPEG goes into PDFs later (the logo on every payslip), so it must decode and stay small in pixels: the
	 * size is read from the header first, so a decompression bomb is refused before anything is decoded.
	 */
	private static void requireSaneImage(byte[] bytes, FileType type) {
		ImageReader reader = ImageIO.getImageReadersByMIMEType(type.contentType()).next();
		boolean tooLarge;
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(in, true, true);
			tooLarge = (long) reader.getWidth(0) * reader.getHeight(0) > MAX_IMAGE_PIXELS;
			if (!tooLarge) {
				reader.read(0);
			}
		}
		catch (IOException | RuntimeException unreadable) {
			// Readers throw unchecked exceptions too on malformed data.
			throw new BusinessRuleException("file", "The image can’t be read. Save it again as PNG or JPG and upload it.");
		}
		finally {
			reader.dispose();
		}
		if (tooLarge) {
			throw new BusinessRuleException("file",
					"The image is larger than 16 megapixels (4000 × 4000). Make it smaller and upload it again.");
		}
	}

	private Path resolve(String path) {
		if (path == null || !STORED_PATH.matcher(path).matches()) {
			throw new NotFoundException("Not a stored file path");
		}
		return root.resolve(path).normalize();
	}

	/** "PNG or JPG", "PDF, JPG or PNG". */
	private static String labels(Set<FileType> allowed) {
		List<String> labels = Arrays.stream(FileType.values()).filter(allowed::contains).map(FileType::label).toList();
		if (labels.size() == 1) {
			return labels.get(0);
		}
		return String.join(", ", labels.subList(0, labels.size() - 1)) + " or " + labels.get(labels.size() - 1);
	}

}
