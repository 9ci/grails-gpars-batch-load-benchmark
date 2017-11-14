package gpbench

import gpbench.benchmarks.*
import gpbench.helpers.BenchmarkHelper
import gpbench.helpers.CsvReader
import grails.core.GrailsApplication
import grails.plugin.dao.DaoUtil
import grails.plugin.dao.GormDaoSupport
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovyx.gpars.GParsPool
import groovyx.gpars.util.PoolUtils
import org.springframework.beans.factory.config.AutowireCapableBeanFactory

class LoaderSimpleService {
	static transactional = false

	private static int POOL_SIZE = 9
	private static int BATCH_SIZE = 50 //this should match the hibernate.jdbc.batch_size in datasources

	RegionDao regionDao
	CountryDao countryDao
	GrailsApplication grailsApplication

	CsvReader csvReader
	BenchmarkHelper benchmarkHelper

	@CompileStatic
	void runBenchMarks() {
		//use default poolsize, it can be updated by passing system property -Dgpars.poolsize=xx
		POOL_SIZE = PoolUtils.retrieveDefaultPoolSize()

		println "--- Environment info ---"
		println "Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 )+ " KB"
		println "Total Memory: " + (Runtime.getRuntime().totalMemory() / 1024 )+ " KB"
		println "Free memory: " + (Runtime.getRuntime().freeMemory() / 1024 ) + " KB"
		println "Available processors: " + Runtime.getRuntime().availableProcessors()
		println "Gpars pool size: " + POOL_SIZE
		println "Autowire enabled: " + System.getProperty("autowire.enabled", "true")
		println "IdGenerator enabled: " + System.getProperty("idgenerator.enabled", "false")


		//load base country and city data which is used by all benchmarks
		benchmarkHelper.truncateTables()
		prepareBaseData()

		if(System.getProperty("warmup", "true").toBoolean()){
			//run benchmarks without displaying numbers to warmup jvm so we get consitent results
			//showing that doing this will drop results below on averge about 10%
			println "- Warmming up JVM"
			//runBenchmark(new GparsBatchInsertBenchmark(false), true)
			//runBenchmark(new GparsBatchInsertBenchmark(), true)
			runBenchmark(new GparsBatchInsertWithoutDaoBenchmark(), true)
			//runBenchmark(new BatchInsertWithDataFlawQueueBenchmark(true), true)
		}

		//real benchmarks starts here
		println "- Running Benchmarks"

		if(System.getProperty("runSingleThreaded", "false").toBoolean()){
			println "-- single threaded - no gpars"
			runBenchmark(new SimpleBatchInsertBenchmark(true))
			//runBenchmark(new CommitEachSaveBenchmark(true))
			//runBenchmark(new OneBigTransactionBenchmark(true))
		}

		runMultiThreads("Pass 1")
		runMultiThreads("Pass 2")

		//runBenchmark(new DataFlawQueueWithScrollableQueryBenchmark())

		System.exit(0)
	}

	void runMultiThreads(String msg){
		println "********* $msg multi-threaded "
		println "- Grails Baseline"
		runBenchmark(new GparsBatchInsertWithoutDaoBenchmark())
		runBenchmark(new GparsBatchInsertWithoutDaoBenchmark(false, true))
		runBenchmark(new GparsBatchInsertWithoutDaoBenchmark(true, false))
		runBenchmark(new GparsBatchInsertWithoutDaoBenchmark(false, false))

		println "\n- Gorm Tools DataFlawQueue"
		runBenchmark(new BatchInsertWithDataFlawQueueBenchmark(true))

		println "\n- Gorm Tools Dao Baseline"
		runBenchmark(new GparsBatchInsertBenchmark())
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"copy"))
		runBenchmark(new GparsBatchInsertBenchmark(true,false))
		runBenchmark(new GparsBatchInsertBenchmark(false,false,"copy"))

		println "\n   -dao with dynamic binding method calls"
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithSetters"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithCopyDomain"))

		println "\n   -dao with static binding method calls"
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"setter"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"copy"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithSetters"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithCopyDomain"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"setter"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"copy"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithSetters"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithCopyDomain"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"setter"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"copy"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithSetters"))
		runBenchmark(new GparsBatchInsertBenchmark(false,true,"bindWithCopyDomain"))

	}

	void prepareBaseData() {
		benchmarkHelper.executeSqlScript("test-tables.sql")
		List<List<Map>> countries = csvReader.read("Country").collate(BATCH_SIZE)
		List<List<Map>> regions = csvReader.read("Region").collate(BATCH_SIZE)
		insert(countries, countryDao)
		insert(regions, regionDao)

		DaoUtil.flushAndClear()

		assert Country.count() == 275
		assert Region.count() == 3953
	}

	@CompileStatic(TypeCheckingMode.SKIP)
	void insert(List<List<Map>> batchList, GormDaoSupport dao) {
		GParsPool.withPool(POOL_SIZE) {
			batchList.eachParallel { List<Map> batch ->
				City.withTransaction {
					batch.each { Map row ->
						dao.insert(row)
					}
					DaoUtil.flushAndClear()
				}
			}
		}
	}

	@CompileStatic(TypeCheckingMode.SKIP)
	void runBenchmark(AbstractBenchmark benchmark, boolean mute = false) {
		if(benchmark instanceof GparsBenchmark) benchmark.poolSize = POOL_SIZE
		if(benchmark instanceof BatchInsertBenchmark) benchmark.batchSize = BATCH_SIZE
		autowire(benchmark)
		benchmark.run()

		if(!mute) println "${benchmark.timeTaken}s for $benchmark.description"
	}

	@CompileStatic
	void autowire(def bean) {
		grailsApplication.mainContext.autowireCapableBeanFactory.autowireBeanProperties(bean, AutowireCapableBeanFactory.AUTOWIRE_BY_NAME, false)
	}

}