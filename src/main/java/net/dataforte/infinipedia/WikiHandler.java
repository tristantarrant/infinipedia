package net.dataforte.infinipedia;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.dataforte.infinispan.amanuensis.AmanuensisIndexWriter;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.infinispan.Cache;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class WikiHandler extends DefaultHandler {
	StringBuilder sb;
	DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
	private int count = 0;
	private String title;
	private String text;
	private boolean redirect;
	private String id;
	private AmanuensisIndexWriter iw;
	private int commitBlock;
	private int sleepBlock = 20000;
	private long sleepTime = 120000;
	private int maxPages;
	private long startTime;
	private long endTime;
	private Cache<String, String> dataCache;
	
	public WikiHandler(AmanuensisIndexWriter iw, int commitBlock, int maxPages, Cache<String, String> dataCache) {
		this.iw = iw;
		this.commitBlock = commitBlock;
		this.maxPages = maxPages;
		this.dataCache = dataCache;
	}

	@Override
	public void startDocument() throws SAXException {
		startTime = System.currentTimeMillis();
		System.out.printf("Indexing Started at %s\n", df.format(new Date()));
	}

	@Override
	public void endDocument() throws SAXException {
		endTime = System.currentTimeMillis();
		System.out.printf("Finished indexing %d pages at %s\n", count, df.format(new Date()));		
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		sb = new StringBuilder();
		if("page".equals(qName)) {
			redirect = false;
			id = null;
			title = null;
			text = null;
		} else if("redirect".equals(qName)) {
			redirect = true;
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if("title".equals(qName)) {
			title = sb.toString();
			++count;
		} else if("text".equals(qName)) {
			text = sb.toString();
		} else if("id".equals(qName)) {
			if(id==null) {
				id = sb.toString();
			}
		} else if("page".equals(qName)) {
			// Don't index redirects
			if(!redirect && id!=null && title!=null && text!=null) {
				//System.out.printf("%s> %s\n", id, title);
				Document doc = new Document();
				doc.add(new Field("id", id, Store.YES, Index.NOT_ANALYZED));
				doc.add(new Field("title", title, Store.YES, Index.NOT_ANALYZED));
				doc.add(new Field("text", text, Store.NO, Index.ANALYZED));
				try {
					dataCache.put(id, text);
					iw.addDocument(doc);					
					if(count % sleepBlock == 0) {
						System.out.println("Sleeping...");
						Thread.sleep(sleepTime);
					}
				} catch (Exception e) {
					throw new SAXException(e);
				}
			}
			if(count==maxPages) {
				endTime = System.currentTimeMillis();
				long totalTime = (endTime-startTime)/1000l;
				System.out.printf("Indexed %d pages in %d seconds (%.2f pages per second)\n", count, totalTime, ((float)count/totalTime));
				throw new ParsingCompleteException("Finished Parsing");
			}
		}
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		sb.append(ch, start, length);
	}

	public int getCount() {
		return count;
	}
	
	

}
