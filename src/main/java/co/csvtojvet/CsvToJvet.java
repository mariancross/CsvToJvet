/* Copyright (c) 2011, BBC Research & Development <davidf@rd.bbc.co.uk>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */


package co.csvtojvet;

import java.util.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellUtil;

import au.com.bytecode.opencsv.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;


public class CsvToJvet
{
	static class HVCResult {
		int qp;
		/* not using a primitive type for non-essential fields so that
		 * optionality can be handled using null */
		Double kbps;
		Double psnr_y;
		Double psnr_cb;
		Double psnr_cr;
		Double runtime_enc;
		Double runtime_dec;
		String id;

		static public class OrderByQP implements Comparator<HVCResult> {
			public int compare(HVCResult a, HVCResult b) {
				if (a.qp < b.qp) return -1;
				if (a.qp > b.qp) return 1;
				return 0;
			}
		}
	}

	static class HVCSequence {
		HVCSequence(String name) {
			this.name = name;
			results = newHashMap();
		}

		/* Name of the sequence */
		String name;

		/* each sequence contains one or more results */
		Map<Integer, HVCResult> results;
	}

	static class HVCConfiguration {
		HVCConfiguration(String name) {
			this.name = name;
			sequences = newHashMap();
		}

		/* name of the configuratoin */
		String name;

		/* each configuration contains a list of sequences */
		Map<String, HVCSequence> sequences;
	}

	static class HVCExperiment {
		HVCExperiment(String name) {
			this.name = name;
			configurations = newHashMap();
			max_results = 416; // Max number of results taking into account the CTC for JVET on 16/05/2018
		}

		/* name of the experiment */
		String name;
		int max_results;

		/* an experiment contains one or more configurations */
		Map<String, HVCConfiguration> configurations;
		
		void populateSheet(XSSFSheet sheet)
		{
			for (HVCConfiguration cfg: configurations.values()) {
				for (HVCSequence seq: cfg.sequences.values()) {
					Iterator<Map.Entry<Integer, HVCResult>> it = seq.results.entrySet().iterator();
					while (it.hasNext())
					{
						HVCResult r = it.next().getValue();
					  
						for (int rowIndex = 0; rowIndex < max_results; rowIndex++){
						    Row row = CellUtil.getRow(rowIndex, sheet);
						    Cell cell = CellUtil.getCell(row, 0);
						    String cell_id = cell.getStringCellValue();
						    
						    if(cell_id.equals(r.id))
						    {
						    	row.getCell(1).setCellValue(r.kbps);
						    	row.getCell(2).setCellValue(r.psnr_y);
						    	row.getCell(3).setCellValue(r.psnr_cb);
						    	row.getCell(4).setCellValue(r.psnr_cr);
						    	row.getCell(5).setCellValue(r.runtime_enc);
						    	row.getCell(6).setCellValue(r.runtime_dec);
						    	it.remove();
						    }
						}	
					 }

				}
			}
		}
	}

	static <E> TreeSet<E> newTreeSet() { return new TreeSet<E>(); }
	static <K,V> Map<K,V> newHashMap() { return new HashMap<K,V>(); }


	/**
	 * parse a row in CSV format, populating the internal tree
	 */
	static void parseLine(HVCExperiment data, String line) throws java.io.IOException
	{
		/* parse a row from csv, and populate the internal
		 * representation tree */
		
		CSVParser csvParser = new CSVParser();
		
        // Loading field into an array of strings
        String[] csv_row = csvParser.parseLine(line);
	
		
		//String[] csv_row = CSVUtils.parseLine(line);
		/* [0] = configuration,
		 * [1] = sequence,
		 * [2] = qp,
		 * [3] = bitrate,
		 * [4] = psnr_y,
		 * [5] = psnr_cb,
		 * [6] = psnr_cr,
		 * [7] = runtime_enc,
		 * [8] = runtime_dec,
		 */
		HVCResult r = new HVCResult();
		double qp = Double.parseDouble(csv_row[2]);
		r.qp = (int) qp;
		try { r.kbps = Double.parseDouble(csv_row[3]); } catch (NumberFormatException e) {}
		try { r.psnr_y = Double.parseDouble(csv_row[4]); } catch (NumberFormatException e) {}
		try { r.psnr_cb = Double.parseDouble(csv_row[5]); } catch (NumberFormatException e) {}
		try { r.psnr_cr = Double.parseDouble(csv_row[6]); } catch (NumberFormatException e) {}
		try { r.runtime_enc = Double.parseDouble(csv_row[7]); } catch (NumberFormatException e) {}
		try { r.runtime_dec = Double.parseDouble(csv_row[8]); } catch (NumberFormatException e) {}

		String conf_name = csvconfig_to_xlsconfig.get(csv_row[0]);
		if (null == conf_name) {
			System.out.printf("Error reading input configuration %s. Ignoring.\n", csv_row[0]);
			return;
		}
		HVCConfiguration conf = data.configurations.get(conf_name);
		if (null == conf) {
			conf = new HVCConfiguration(conf_name);
			data.configurations.put(conf_name, conf);
		}

		String seq_name = csv_row[1].trim();
		HVCSequence seq = conf.sequences.get(seq_name);
		if (null == seq) {
			seq = new HVCSequence(seq_name);
			conf.sequences.put(seq_name, seq);
		}

		// Fill the id of the test point
		r.id = seq_name + ".Q" + r.qp + ".jvet10." + conf_name;
		
		seq.results.put(new Integer(r.qp), r);

	}

	/**
	 * parse an input file containing results from an experiment, populating
	 * the internal tree using a given name
	 * @param filename file to open
	 * @param name human readable label for experiment
	 */
	static HVCExperiment load(String filename, String name) throws java.io.IOException
	{
		HVCExperiment data = new HVCExperiment(name);

		BufferedReader br = new BufferedReader(new FileReader(filename));
		br.readLine(); // skip the header.  TODO: validate
		for (String line = br.readLine(); line != null; line = br.readLine()) {
			parseLine(data, line);
		}
		br.close();

		return data;
	}

	/**
	 * insert the human readable names of the experiment in the summary sheet
	 * of a workbook.
	 */
	static void updateExperimentNames(XSSFSheet sheet, List<HVCExperiment> experiments)
	{
		
		HVCExperiment exp0 = experiments.get(0); //reference 1
		HVCExperiment exp1 = experiments.get(1); //reference 2
		HVCExperiment exp2 = experiments.get(2); //test
		
		sheet.getRow(2).getCell(14).setCellValue(exp0.name); /* row 3, column O */
		sheet.getRow(3).getCell(14).setCellValue(exp2.name); /* row 4, column O */
		sheet.getRow(4).getCell(14).setCellValue(exp1.name); /* row 5, column O */
	}

	public static void main(String[] args) throws Exception
	{
		List<HVCExperiment> all_data = new LinkedList<HVCExperiment>();

		String template_name = args[0];
		String output_name = args[1];

		for (int i = 2; i < args.length; i++) {
			String filename = args[i];
			String name = args[++i];
			all_data.add(load(filename, name));
		}

		FileInputStream file_template = new FileInputStream(template_name);
		//HSSFWorkbook wb = new HSSFWorkbook(file_template, true);
		XSSFWorkbook wb = new XSSFWorkbook(file_template);
		file_template.close();

		updateExperimentNames(wb.getSheet("Summary"), all_data);

		// Get all experiments sheets and populate them
		XSSFSheet TMSheet = wb.getSheetAt(6);
		XSSFSheet BMSSheet = wb.getSheetAt(7);
		XSSFSheet TestSheet = wb.getSheetAt(8);
		TMSheet.setForceFormulaRecalculation(true);
		BMSSheet.setForceFormulaRecalculation(true);
		TestSheet.setForceFormulaRecalculation(true);
		all_data.get(0).populateSheet(TMSheet);
		all_data.get(1).populateSheet(BMSSheet);
		all_data.get(2).populateSheet(TestSheet);


		// Output ignored test points
		for (HVCExperiment exp: all_data) {
			for (HVCConfiguration cfg: exp.configurations.values()) {
				for (HVCSequence seq: cfg.sequences.values()) {
					for (HVCResult r: seq.results.values()) {
						System.out.println("Warning: "+exp.name+", "+cfg.name+", "+seq.name+", qp"+r.qp+" not consumed");
					}
				}
			}
		}

		/* write out */
		FileOutputStream out = new FileOutputStream(output_name);
		wb.write(out);
		out.close();
		wb.close();
	}
	
	@SuppressWarnings("serial")
	public static final Map<String, String> csvconfig_to_xlsconfig = new HashMap<String, String>() {{
		put("i_main10", "ai");
		put("ra_main10", "ra");
		put("ld_main10", "lb");
		put("ld_P_main10", "lp");
	}};

}

