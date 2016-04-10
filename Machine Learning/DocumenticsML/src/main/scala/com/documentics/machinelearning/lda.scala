package com.documentics.machinelearning

import java.io.PrintStream

import org.apache.log4j.{Level, Logger}
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.feature.{CountVectorizer, CountVectorizerModel, RegexTokenizer, StopWordsRemover}
import org.apache.spark.mllib.clustering.{DistributedLDAModel, EMLDAOptimizer, LDA, OnlineLDAOptimizer}
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.{SparkConf, SparkContext}
import scopt.OptionParser

object LDAExample {

      private case class Params(
                                 input: Seq[String] = Seq.empty,
                                 k: Int = 20,
                                 maxIterations: Int = 10,
                                 docConcentration: Double = -1,
                                 topicConcentration: Double = -1,
                                 vocabSize: Int = 10000,
                                 stopwordFile: String = "",
                                 algorithm: String = "em",
                                 checkpointDir: Option[String] = None,
                                 checkpointInterval: Int = 10) extends AbstractParams[Params]

      def main(args: Array[String]) {
        val defaultParams = Params()

        val parser = new OptionParser[Params]("LDAExample") {
          head("LDAExample: an example LDA app for plain text data.")
          opt[Int]("k")
            .text(s"number of topics. default: ${defaultParams.k}")
            .action((x, c) => c.copy(k = x))
          opt[Int]("maxIterations")
            .text(s"number of iterations of learning. default: ${defaultParams.maxIterations}")
            .action((x, c) => c.copy(maxIterations = x))
          opt[Double]("docConcentration")
            .text(s"amount of topic smoothing to use (> 1.0) (-1=auto)." +
              s"  default: ${defaultParams.docConcentration}")
            .action((x, c) => c.copy(docConcentration = x))
          opt[Double]("topicConcentration")
            .text(s"amount of term (word) smoothing to use (> 1.0) (-1=auto)." +
              s"  default: ${defaultParams.topicConcentration}")
            .action((x, c) => c.copy(topicConcentration = x))
          opt[Int]("vocabSize")
            .text(s"number of distinct word types to use, chosen by frequency. (-1=all)" +
              s"  default: ${defaultParams.vocabSize}")
            .action((x, c) => c.copy(vocabSize = x))
          opt[String]("stopwordFile")
            .text(s"filepath for a list of stopwords. Note: This must fit on a single machine." +
              s"  default: ${defaultParams.stopwordFile}")
            .action((x, c) => c.copy(stopwordFile = x))
          opt[String]("algorithm")
            .text(s"inference algorithm to use. em and online are supported." +
              s" default: ${defaultParams.algorithm}")
            .action((x, c) => c.copy(algorithm = x))
          opt[String]("checkpointDir")
            .text(s"Directory for checkpointing intermediate results." +
              s"  Checkpointing helps with recovery and eliminates temporary shuffle files on disk." +
              s"  default: ${defaultParams.checkpointDir}")
            .action((x, c) => c.copy(checkpointDir = Some(x)))
          opt[Int]("checkpointInterval")
            .text(s"Iterations between each checkpoint.  Only used if checkpointDir is set." +
              s" default: ${defaultParams.checkpointInterval}")
            .action((x, c) => c.copy(checkpointInterval = x))
          arg[String]("<input>...")
            .text("input paths (directories) to plain text corpora." +
              "  Each text file line should hold 1 document.")
            .unbounded()
            .required()
            .action((x, c) => c.copy(input = c.input :+ x))
        }

        parser.parse(args, defaultParams).map { params =>
          run(params)
        }.getOrElse {
          parser.showUsageAsError
          sys.exit(1)
        }
      }

      private def run(params: Params) {
        System.setProperty("hadoop.home.dir", "F:\\winutils")
        val conf = new SparkConf().setAppName(s"LDAExample with $params").setMaster("local[*]").set("spark.driver.memory", "4g").set("spark.executor.memory", "4g")
        val sc = new SparkContext(conf)

        Logger.getRootLogger.setLevel(Level.WARN)

        val topic_output = new PrintStream("ldaData2/Results/13domain_LDA_5.txt")
        // Load documents, and prepare them for LDA.
        val preprocessStart = System.nanoTime()
        val (corpus, vocabArray, actualNumTokens) =
          preprocess(sc, params.input, params.vocabSize, params.stopwordFile)
        corpus.cache()
        val actualCorpusSize = corpus.count()
        val actualVocabSize = vocabArray.length
        val preprocessElapsed = (System.nanoTime() - preprocessStart) / 1e9

        println()
        println(s"Corpus summary:")
        println(s"\t Training set size: $actualCorpusSize documents")
        println(s"\t Vocabulary size: $actualVocabSize terms")
        println(s"\t Training set size: $actualNumTokens tokens")
        println(s"\t Preprocessing time: $preprocessElapsed sec")
        println()


        topic_output.println()
        topic_output.println(s"Corpus summary:")
        topic_output.println(s"\t Training set size: $actualCorpusSize documents")
        topic_output.println(s"\t Vocabulary size: $actualVocabSize terms")
        topic_output.println(s"\t Training set size: $actualNumTokens tokens")
        topic_output.println(s"\t Preprocessing time: $preprocessElapsed sec")
        topic_output.println()

        // Run LDA.
        val lda = new LDA()

        val optimizer = params.algorithm.toLowerCase match {
          case "em" => new EMLDAOptimizer
          // add (1.0 / actualCorpusSize) to MiniBatchFraction be more robust on tiny datasets.
          case "online" => new OnlineLDAOptimizer().setMiniBatchFraction(0.05 + 1.0 / actualCorpusSize)
          case _ => throw new IllegalArgumentException(
            s"Only em, online are supported but got ${params.algorithm}.")
        }

        lda.setOptimizer(optimizer)
          .setK(params.k)
          .setMaxIterations(params.maxIterations)
          .setDocConcentration(params.docConcentration)
          .setTopicConcentration(params.topicConcentration)
          .setCheckpointInterval(params.checkpointInterval)
        if (params.checkpointDir.nonEmpty) {
          sc.setCheckpointDir(params.checkpointDir.get)
        }
        val startTime = System.nanoTime()
        val ldaModel = lda.run(corpus)
        val elapsed = (System.nanoTime() - startTime) / 1e9

        println(s"Finished training LDA model.  Summary:")
        println(s"\t Training time: $elapsed sec")


        topic_output.println(s"Finished training LDA model.  Summary:")
        topic_output.println(s"\t Training time: $elapsed sec")

        if (ldaModel.isInstanceOf[DistributedLDAModel]) {
          val distLDAModel = ldaModel.asInstanceOf[DistributedLDAModel]
          val avgLogLikelihood = distLDAModel.logLikelihood / actualCorpusSize.toDouble
          println(s"\t Training data average log likelihood: $avgLogLikelihood")
          println()
          topic_output.println(s"\t Training data average log likelihood: $avgLogLikelihood")
          topic_output.println()
        }

        // Print the topics, showing the top-weighted terms for each topic.
        val topicIndices = ldaModel.describeTopics(maxTermsPerTopic = actualVocabSize)
        val topics = topicIndices.map { case (terms, termWeights) =>
          terms.zip(termWeights).map { case (term, weight) => (vocabArray(term.toInt), weight) }
        }
        println(s"${params.k} topics:")
        topic_output.println(s"${params.k} topics:")
        topics.zipWithIndex.foreach { case (topic, i) =>
          println(s"TOPIC $i")
          // topic_output.println(s"TOPIC $i")
          topic.foreach { case (term, weight) =>
            println(s"$term\t$weight")
            topic_output.println(s"TOPIC_$i;$term;$weight")
          }
          println()
          topic_output.println()
        }
        topic_output.close()
        sc.stop()
      }

      /**
        * Load documents, tokenize them, create vocabulary, and prepare documents as term count vectors.
        *
        * @return (corpus, vocabulary as array, total token count in corpus)
        */
      private def preprocess(
                              sc: SparkContext,
                              paths: Seq[String],
                              vocabSize: Int,
                              stopwordFile: String): (RDD[(Long, Vector)], Array[String], Long) = {

        val sqlContext = SQLContext.getOrCreate(sc)
        import sqlContext.implicits._

        // Get dataset of document texts
        // One document per line in each text file. If the input consists of many small files,
        // this can result in a large number of small partitions, which can degrade performance.
        // In this case, consider using coalesce() to create fewer, larger partitions.
        val df = sc.textFile(paths.mkString(",")).toDF("docs")
        val customizedStopWords: Array[String] = if (stopwordFile.isEmpty) {
          Array.empty[String]
        } else {
          val stopWordText = sc.textFile(stopwordFile).collect()
          stopWordText.flatMap(_.stripMargin.split("\\s+"))
        }
        val tokenizer = new RegexTokenizer()
          .setInputCol("docs")
          .setOutputCol("rawTokens")
        val stopWordsRemover = new StopWordsRemover()
          .setInputCol("rawTokens")
          .setOutputCol("tokens")
        stopWordsRemover.setStopWords(stopWordsRemover.getStopWords ++ customizedStopWords)
        val countVectorizer = new CountVectorizer()
          .setVocabSize(vocabSize)
          .setInputCol("tokens")
          .setOutputCol("features")

        val pipeline = new Pipeline()
          .setStages(Array(tokenizer, stopWordsRemover, countVectorizer))

        val model = pipeline.fit(df)
        val documents = model.transform(df)
          .select("features")
          .rdd
          .map { case Row(features: Vector) => features }
          .zipWithIndex()
          .map(_.swap)

        (documents,
          model.stages(2).asInstanceOf[CountVectorizerModel].vocabulary, // vocabulary
          documents.map(_._2.numActives).sum().toLong) // total token count
      }
    }

// scalastyle:on println
