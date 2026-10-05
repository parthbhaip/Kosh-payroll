package com.kosh;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** Proves the PDF and Excel libraries added in T01 work the way later tasks rely on. */
class DocumentLibrariesTest {

	@Test
	void openPdfWritesTableAndTextCanBeExtracted() throws Exception {
		var out = new ByteArrayOutputStream();
		var document = new Document();
		PdfWriter.getInstance(document, out);
		document.open();
		document.add(new Paragraph("Payslip for September 2026"));
		var table = new PdfPTable(2);
		table.addCell("Basic");
		table.addCell("15000.00");
		document.add(table);
		document.close();

		try (var reader = new PdfReader(out.toByteArray())) {
			String text = new PdfTextExtractor(reader).getTextFromPage(1);
			assertThat(text).contains("Payslip for September 2026", "Basic", "15000.00");
		}
	}

	@Test
	void poiWritesAndReadsXlsx() throws Exception {
		var out = new ByteArrayOutputStream();
		try (var workbook = new XSSFWorkbook()) {
			var row = workbook.createSheet("Register").createRow(0);
			row.createCell(0).setCellValue("KSH-0001");
			row.createCell(1).setCellValue(15000.00);
			workbook.write(out);
		}

		try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
			var row = workbook.getSheet("Register").getRow(0);
			assertThat(row.getCell(0).getStringCellValue()).isEqualTo("KSH-0001");
			assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(15000.00);
		}
	}

}
