



import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bson.Document;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import static com.mongodb.client.model.Filters.*;
import com.mongodb.client.model.IndexOptions;
import java.util.Arrays;
import java.util.List;

//TODO - Change from System.println to a logging framework?

public class LoadRunner {

	MongoClient mongoClient;
	

	private void PrepareSystem(POCTestOptions testOpts,POCTestResults results)
	{
		MongoDatabase db;
		MongoCollection<Document>  coll;
		//Create indexes and suchlike
		db = mongoClient.getDatabase(testOpts.databaseName);
		coll = db.getCollection(testOpts.collectionName);
		if(testOpts.emptyFirst)
		{
			coll.drop();
		}
		

		
		for(int x=0;x<testOpts.secondaryidx;x++)
		{
			coll.createIndex(new Document("fld"+x,1));
		}
        if (testOpts.fulltext) 
        {
            IndexOptions options = new IndexOptions();
            options.background(true);
            BasicDBObject weights = new BasicDBObject();
            weights.put("sleutel", 15);
            weights.put("_fulltext.text", 5);
            options.weights(weights);
                //indexOptions.put("weights", weights);
            Document index = new Document();
            index.put("$**", "text");
            coll.createIndex(index, options);
        }
		
		results.initialCount = coll.count();
		//Now have a look and see if we are sharded
		//And how many shards and make sure that the collection is sharded
		if(! testOpts.singleserver) {
			ConfigureSharding(testOpts);
		}
		
		
	}
	
	private void ConfigureSharding(POCTestOptions testOpts)
	{
		MongoDatabase admindb = mongoClient.getDatabase("admin");
		Document cr = admindb.runCommand(new Document("serverStatus",1));
		if(cr.getDouble("ok") == 0)
		{
			System.out.println(cr.toJson());
			return;
		}

		if (cr.get("process").equals("mongos"))
		{
			testOpts.sharded = true;
			//Turn the auto balancer off - good code rarely needs it running constantly 
			MongoDatabase configdb = mongoClient.getDatabase("config");
			if(configdb != null)
			{
				MongoCollection<Document>  settings = configdb.getCollection("settings");
				settings.updateOne(eq("_id","balancer"), new Document("$set",new Document("stopped",true)));
				//System.out.println("Balancer disabled");
			}
			try {
				cr = admindb.runCommand(new Document("enableSharding",testOpts.databaseName));
			} catch (Exception e)
			{
				if(!e.getMessage().contains("already enabled"))
					System.out.println(e.getMessage());
			}
			

			
			try{
			cr = admindb.runCommand(new Document("shardCollection",
					testOpts.databaseName+"."+testOpts.collectionName).append("key", new Document("_id",1)));
			} catch (Exception e)
			{
				if(!e.getMessage().contains("already sharded"))
					System.out.println(e.getMessage());
			}

			
			//See how many shards we have in the system - and get a list of their names
			
			MongoCollection<Document>  shards = configdb.getCollection("shards");
			//DBCursor cursor = (DBCursor) shards.find();
			testOpts.numShards = (int)shards.count();
		}
	}
	
	public void RunLoad(POCTestOptions testOpts, POCTestResults testResults) {

		PrepareSystem(testOpts,testResults);
		// Report on progress by looking at testResults
		Runnable reporter = new POCTestReporter(testResults,mongoClient,testOpts);
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
		executor.scheduleAtFixedRate(reporter, 0, testOpts.reportTime, TimeUnit.SECONDS);

		
		// Using a thread pool we keep filled
		ExecutorService testexec = Executors
				.newFixedThreadPool(testOpts.numThreads);

		// Allow for multiple clients to run - 
		// Check for testOpts.threadIdStart - this should be an integer to start
		// the 'workerID' for each set of threads.
		int threadIdStart = testOpts.threadIdStart;
		System.out.println("threadIdStart="+threadIdStart);
		for (int i = threadIdStart; i < (testOpts.numThreads+threadIdStart); i++) {
			testexec.execute(new MongoWorker(mongoClient, testOpts, testResults,i));
		}

		testexec.shutdown();
	
		try {
			testexec.awaitTermination(Long.MAX_VALUE,
					TimeUnit.SECONDS);
			//System.out.println("All Threads Complete: " + b);
			executor.shutdown();
		} catch (InterruptedException e) {
			System.out.println(e.getMessage());
			
		}
	}

	public LoadRunner(POCTestOptions testOpts) {
		try {
            if (testOpts.username != null) 
            {
                MongoCredential credentials = MongoCredential.createCredential(testOpts.username, testOpts.authDatabase, testOpts.password);
                mongoClient = new MongoClient(new ServerAddress(testOpts.connectionDetails), Arrays.asList(credentials));
            } else 
            {
                mongoClient = new MongoClient(new MongoClientURI(
                        testOpts.connectionDetails));
            }
		} catch (Exception e) {
		
			e.printStackTrace();
		}
	}
}
