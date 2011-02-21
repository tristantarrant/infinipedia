package net.dataforte.infinipedia;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jline.console.ConsoleReader;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.Completer;
import jline.console.completer.FileNameCompleter;
import jline.console.history.FileHistory;
import net.dataforte.commons.resources.SystemUtils;
import net.dataforte.infinispan.amanuensis.AmanuensisIndexReader;
import net.dataforte.infinispan.amanuensis.AmanuensisIndexWriter;
import net.dataforte.infinispan.amanuensis.AmanuensisManager;
import net.dataforte.infinispan.amanuensis.DefaultWriterConfigurator;
import net.dataforte.infinispan.amanuensis.IndexerException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.Version;
import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.lucene.InfinispanDirectory;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.stats.Stats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Infinipedia {
	static final Logger log = LoggerFactory.getLogger(Infinipedia.class);

	private Thread indexerThread;
	private DefaultCacheManager cacheManager;
	private InfinispanDirectory directory;
	private Analyzer analyzer;
	String cassandraHost = "localhost";
	int cassandraPort = 9160;
	private InfinipediaIndexer indexer;
	private Cache<String, String> dataCache;
	private ScoreDoc[] scoreDocs;
	private AmanuensisManager amanuensisManager;
	private AmanuensisIndexReader indexReader;
	private AmanuensisIndexWriter indexWriter;

	private IndexSearcher searcher;

	public enum InfinipediaState {
		IDLE, CONNECTED, INDEXING, QUITTING
	};

	public Infinipedia() {
		analyzer = new WikipediaAnalyzer();
	}

	private static URL getLocalResource(String name) {
		return Thread.currentThread().getContextClassLoader().getResource(name);
	}

	private void doQuery(String query) {
		IndexReader reader = null;
		try {
			QueryParser parser = new QueryParser(Version.LUCENE_29, "text", analyzer);
			Query parsedQuery = parser.parse(query);

			reader = indexReader.get();
			if (searcher != null) {
				searcher.close();
			}

			searcher = new IndexSearcher(reader);
			TopDocs topDocs = searcher.search(parsedQuery, null, 20);
			scoreDocs = topDocs.scoreDocs;

			for (int i = 0; i < scoreDocs.length; i++) {
				Document doc = searcher.doc(scoreDocs[i].doc);
				System.out.printf("%d) %s (%03.2f)\n", i, doc.get("title"), scoreDocs[i].score);
			}

		} catch (Exception e) {
			log.error("", e);
		} finally {
			indexReader.release(reader);
		}
	}

	private void showDoc(int i) {
		if (scoreDocs == null) {
			System.out.println("No current search");
			return;
		}
		if (i >= scoreDocs.length) {
			System.out.println("No such item");
		}
		try {
			Document doc = searcher.doc(scoreDocs[i].doc);
			System.out.println(doc.get("title"));
			System.out.println("==================");
			System.out.println(dataCache.get(doc.get("id")));
			System.out.println("==================");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private String readConfig() throws IOException {
		BufferedReader r = new BufferedReader(new InputStreamReader(getLocalResource("infinispan.xml").openStream()));
		StringBuilder configSB = new StringBuilder();
		for (String line = r.readLine(); line != null; line = r.readLine()) {
			configSB.append(line);
		}
		r.close();
		return configSB.toString();
	}

	private void startCache() throws IOException, IndexerException {
		String config = readConfig();
		config = config.replaceAll("\\$\\{cassandra.host\\}", cassandraHost);
		config = config.replaceAll("\\$\\{cassandra.port\\}", Integer.toString(cassandraPort));

		cacheManager = new DefaultCacheManager(new ByteArrayInputStream(config.getBytes()), false);

		// Initialize the caches
		dataCache = cacheManager.getCache("dataCache");
		Cache<Object, Object> lockCache = cacheManager.getCache("volatileCache");
		Cache<Object, Object> metadataCache = cacheManager.getCache("metadataCache");
		Cache<Object, Object> chunksCache = cacheManager.getCache("chunksCache");

		// Initialize the directory
		directory = new InfinispanDirectory(metadataCache, chunksCache, lockCache, "wikiIndex", 8 * 1024 * 1024);
		// Initialize the index
		if(!IndexReader.indexExists(directory)) {
			IndexWriter iw = new IndexWriter(directory, analyzer, MaxFieldLength.UNLIMITED);
			iw.close();
			log.info("Initialized empty index");
		}

		cacheManager.start();

		amanuensisManager = new AmanuensisManager(cacheManager);
		DefaultWriterConfigurator writerConfigurator = new DefaultWriterConfigurator();
		writerConfigurator.setMaxMergeMB(8);
		writerConfigurator.setMinMergeMB(2);
		amanuensisManager.setWriterConfigurator(writerConfigurator);
		amanuensisManager.setAnalyzer(analyzer);
		indexWriter = amanuensisManager.getIndexWriter(directory);
		indexReader = amanuensisManager.getIndexReader(directory);
	}

	private void stopCache() throws CorruptIndexException, IOException {
		if (cacheManager != null) {
			cacheManager.stop();
		}
	}

	private void doIndex(String wikiDump, int docCount) throws Exception {
		if (indexer == null) {
			indexer = new InfinipediaIndexer(indexWriter);
		}
		indexer.configure(wikiDump, docCount, dataCache);

		indexerThread = new Thread(indexer);
		indexerThread.start();
	}

	private void printInfo() {
		System.out.println("Cluster info");
		System.out.println("============");
		System.out.println("Cluster name: " + cacheManager.getClusterName());
		System.out.println("Local address: " + cacheManager.getAddress());
		System.out.println("Coordinator address: " + cacheManager.getCoordinator());
		System.out.println("Members: " + cacheManager.getMembers());
		System.out.println("Cache info");
		System.out.println("============");
		for (String s : cacheManager.getCacheNames()) {
			AdvancedCache<Object, Object> c = cacheManager.getCache(s).getAdvancedCache();
			Stats stats = c.getStats();
			System.out.println("Cache: " + s);
			System.out.println("\tStatus: " + c.getStatus() + "\tEntries: " + stats.getCurrentNumberOfEntries() + "/" + stats.getTotalNumberOfEntries() + "\tEvictions: " + stats.getEvictions()
					+ "\tHits: " + stats.getHits() + "\tMisses: " + stats.getMisses());
		}
		System.out.println("Index info");
		IndexReader r = null;
		try {
			r = indexReader.get();
			System.out.println("\tDocuments: " + r.numDocs() + "\tDeleted documents: " + r.numDeletedDocs());
		} catch (IndexerException e) {
			log.error("", e);
		} finally {
			indexReader.release(r);
		}

	}

	public static void printHelp() {
		System.out.println("\th or help\t\t\tprint this help");
		System.out.println("\tq or quit\t\t\tquit");
		System.out.println("\tc hostname:port\t\t\tspecifies Cassandra coordinates (defaults to localhost:9160)");
		System.out.println("\ts or start\t\t\tstarts the distributed cache");
		System.out.println("\tindex [wikidump.bz2]\t\tstarts indexing");
		System.out.println("\t?[query]\t\t\tsearches the index");
		System.out.println("\tshow [index]\t\tshows a document from the current search results");
		System.out.println("\tm [message]\t\t sends a message to the coordinator");
		System.out.println("\ti or info\t\t\tprints information about system");
	}

	public static final void main(String args[]) {
		try {
			Infinipedia infinipedia = new Infinipedia();

			System.out.println("Infinipedia");

			String appConfigFolder = SystemUtils.getAppConfigFolder("infinipedia");

			InfinipediaState state = InfinipediaState.IDLE;

			ConsoleReader console = new ConsoleReader();

			File historyFile = new File(appConfigFolder + File.separator + "history");

			FileHistory history = new FileHistory(historyFile);
			console.setHistory(history);
			console.setBellEnabled(false);

			List<Completer> completers = new LinkedList<Completer>();
			completers.add(new FileNameCompleter());
			console.addCompleter(new ArgumentCompleter(completers));

			while (state != InfinipediaState.QUITTING) {
				if (state == InfinipediaState.INDEXING && !infinipedia.indexerThread.isAlive()) {
					state = InfinipediaState.CONNECTED;
				}

				String line = console.readLine(
						"[" + state.toString() + (state == InfinipediaState.CONNECTED ? " " + infinipedia.cacheManager.getAddress() : "")
								+ (state == InfinipediaState.INDEXING ? " " + infinipedia.indexer.getCount() : "") + "]> ").trim();

				if ("".equals(line)) {
					// IGNORE
				} else if ("q".equals(line) || "quit".equals(line)) {
					infinipedia.stopCache();
					state = InfinipediaState.QUITTING;
				} else if ("h".equals(line) || "help".equals(line)) {
					printHelp();
				} else if (line.startsWith("index ")) {
					if (state == InfinipediaState.INDEXING) {
						System.out.println("Indexing already running");
					} else if (state == InfinipediaState.IDLE) {
						System.out.println("Must be started");
					} else {
						infinipedia.doIndex(line.substring(6).trim(), Integer.MAX_VALUE);
						state = InfinipediaState.INDEXING;
					}
				} else if (line.startsWith("c ")) {
					Pattern hostPattern = Pattern.compile("([a-zA-Z;,\\._\\-0-9}]+):([0-9]{1,5})");
					Matcher matcher = hostPattern.matcher(line.substring(2).trim());
					if (matcher.matches()) {
						infinipedia.cassandraHost = matcher.group(1);
						infinipedia.cassandraPort = Integer.parseInt(matcher.group(2));
					} else {
						System.out.println("Parameter must be in the form hostname:port");
					}
				} else if ("s".equals(line) || "start".equals(line)) {
					if (state == InfinipediaState.IDLE) {
						infinipedia.startCache();
						state = InfinipediaState.CONNECTED;
					} else {
						System.out.println("Already started");
					}
				} else if (line.startsWith("?")) {
					if (state == InfinipediaState.IDLE) {
						System.out.println("Must be started");
					} else {
						infinipedia.doQuery(line.substring(1).trim());
					}
				} else if (line.startsWith("show ")) {
					if (state == InfinipediaState.IDLE) {
						System.out.println("Must be started");
					} else {
						infinipedia.showDoc(Integer.parseInt(line.substring(5).trim()));
					}
				} else if ("i".equals(line) || "info".equals(line)) {
					if (state == InfinipediaState.IDLE) {
						System.out.println("Must be started");
					} else {
						infinipedia.printInfo();
					}
				} else {
					System.out.println("Unknown command");
				}
			}

			history.flush();
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		} finally {
			System.exit(0);
		}

	}

}
