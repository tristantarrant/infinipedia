package net.dataforte.infinipedia;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.dataforte.infinispan.amanuensis.AmanuensisIndexWriter;

import org.apache.tools.bzip2.CBZip2InputStream;
import org.infinispan.Cache;
import org.xml.sax.SAXException;

public class InfinipediaIndexer implements Runnable {
	
	private AmanuensisIndexWriter iw;
	private WikiHandler wh;
	private InputStream is;	

	public InfinipediaIndexer(AmanuensisIndexWriter iw) {
		
		this.iw = iw;
	}
	
	public void configure(String wikiDump, int docCount, Cache<String, String> dataCache) throws IOException {
		wh = new WikiHandler(iw, 100, docCount, dataCache);
        
        is = new BufferedInputStream(new FileInputStream(wikiDump));
		// Skip the first two bytes (BZ)
		is.read();
		is.read();
	}

	@Override
	public void run() {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(false);
        factory.setValidating(false);
        try {
			SAXParser parser = factory.newSAXParser();
			try {
				parser.parse(new CBZip2InputStream(is), wh);
			} catch (ParsingCompleteException e) {
				System.out.println("Parsing complete");
			}
			
        } catch (IOException ioe) {
        	
        } catch (SAXException spe) {
        	
        } catch (ParserConfigurationException pce) {
			
		} finally {
        	try {
				is.close();
			} catch (IOException e) {
			}
        }
	}
	
	public int getCount() {
		return wh.getCount();
	}

}
