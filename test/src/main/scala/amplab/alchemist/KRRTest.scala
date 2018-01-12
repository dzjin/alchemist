package alchemist.test.regression

// Spark Core
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.rdd._

// Spark SQL
import org.apache.spark.sql.SparkSession

// Spark MLLib
import org.apache.spark.mllib.linalg.{Vector, Vectors, Matrix, Matrices}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector}
import org.apache.spark.mllib.linalg.distributed.{RowMatrix, IndexedRow, IndexedRowMatrix}

// Alchemist
import amplab.alchemist._

// Others
import scala.math
import java.io._
import utils._

object AlchemistRFMClassification {

    def main(args: Array[String]) : Unit = {
        // Parse parameters from command line
        val filepath: String = args(0)
        val format: String = args(1)
        val numFeatures: Int = args(2).toInt
        val gamma: Double = args(3).toDouble
        val numClass: Int = args(4).toInt 
        val numSplits: Int = 100 
        val maxIter: Int = 500

        // Launch Spark
        var t1 = System.nanoTime()
        val spark = (SparkSession.builder()
                        .appName("Test Alchemist Random Feature Regression")
                        .getOrCreate())
        val sc: SparkContext = spark.sparkContext
        sc.setLogLevel("ERROR")
        var t2 = System.nanoTime()

        println(s"Time to start Spark session was ${(t2 - t1) * 1.0E-9} sec")
        println(" ")

        // Launch Alchemist
        t1 = System.nanoTime()
        val al = new Alchemist(sc)
        t2 = System.nanoTime()
        println(s"Took ${(t2 -t1)*1.0E-9} seconds to launch Alchemist")
        println(" ")

        // Load data and perform RFM
        val rddRaw: RDD[(Int, (Int, Array[Double]))] = format.toUpperCase match {
            case "LIBSVM" => RDDToIndexedRDD(loadLibsvmData(spark, filepath, numSplits))
            case "CSV" => RDDToIndexedRDD(loadCsvData(spark, filepath, numSplits))
        }
        val (rddA, rddTargets) = randomFeatureMap(rddRaw, numFeatures)
        rddRaw.unpersist()
        val rddOneHot: RDD[(Int, Array[Double])] = rddTargets.mapValues(oneHotEncode(_, numClass)).cache()
        rddTargets.unpersist()

        // Convert the Spark RDD into RDDs appropriate for calling Alchemist on:
        // two IndexedRowMatrix RDDS for the LHS and the RHS
        println("Starting the conversion to IndexedRowMatrices needed to use Alchemist CG implementation")
        println(" ")
        var t3 = System.nanoTime()
        val rddB : RDD[IndexedRow] = rddOneHot.map{ case (index, encoding) => new IndexedRow(index, new DenseVector(encoding)) }.cache()
        rddOneHot.unpersist()
        val numPts = rddA.count()
        val matA : IndexedRowMatrix = new IndexedRowMatrix(rddA, numPts, numFeatures)
        val matB : IndexedRowMatrix = new IndexedRowMatrix(rddB, numPts, numClass)
        var t4 = System.nanoTime()
        println("Finished the conversion")
        println(" ")

        // Train ridge regression via CG
        val alMatA = AlMatrix(al, matA)
        val alMatB = AlMatrix(al, matB)
        var t5 = System.nanoTime()
        val alMatX = al.factorizedCGKRR(alMatA, alMatB, gamma, maxIter)
        var t6 = System.nanoTime()
        val rddX = alMatX.getIndexedRowMatrix()
        var t7 = System.nanoTime()

        // Compute misclassification rate 
        val alMatPredict = al.matMul(alMatA, alMatX)
        val predictedEncodings : IndexedRowMatrix = alMatPredict.getIndexedRowMatrix()
        val matPredictedEncodings : RDD[(Long, Array[Double])] = predictedEncodings.rows.map(row => (row.index, row.vector.toArray))
        val matTrueEncodings : RDD[(Long, Array[Double])]= rddB.map(row => (row.index, row.vector.toArray))
        val misClassified : Double = matPredictedEncodings.join(matTrueEncodings).mapValues{
          case (enc1, enc2) => {
            val pred : Int = enc1.indexOf(enc1.max)
            val label : Int  = enc2.indexOf(enc2.max)
            if (pred != label) 1 else 0
          }
        }.values.collect.sum.toDouble


        println(s"Alchemist timing (sec); conversion: ${(t4 - t3)*1.0E-9}, send: ${(t5-t4)*1.0E-9}, compute: ${(t6-t5)*1.0E-9}, receive: ${(t7-t6)*1.0E-9}")
        println(s"Misclassification rate: ${misClassified/numPts}")

        al.stop
        sc.stop
    }

    def loadCsvData(spark: SparkSession, filepath: String, numSplits: Int) : RDD[(Int, Array[Double])] = {
        // Load data from file
        val t1 = System.nanoTime()
        val df = spark.read.format("csv").load(filepath)
        val rdd: RDD[Array[Double]] = df.rdd.map(vec => Vectors.parse(vec.toString).toArray).persist()
        val d: Int = rdd.take(1)(0).size
        val rdd2: RDD[(Int, Array[Double])] = rdd.map(arr => (arr(0).toInt-1, arr.slice(1, d))).persist()
        val n = rdd2.count()
        val t2 = System.nanoTime()
        println(s"Feature matrix is $n by ${d-1}")
        println(s"Loaded the data in ${(t2 - t1)*1.0E-9} secs")
        println(" ")
        rdd.unpersist()
        rdd2
    }

    def loadLibsvmData(spark: SparkSession, filepath: String, numSplits: Int) : RDD[(Int, Array[Double])] = {
      // Load data from file
      val t1 = System.nanoTime()
      val df = spark.read.format("libsvm").load(filepath)
      val rdd: RDD[(Int, Array[Double])] = df.rdd
                        .map(pair => (pair(0).toString.toFloat.toInt, Vectors.parse(pair(1).toString).toArray))
                        .persist()
      val n = rdd.count()
      val d = rdd.take(1)(0)._2.size
      val t2 = System.nanoTime()
      println(s"Feature matrix is $n by $d")
      println(s"Loaded the data in ${(t2 - t1)*1.0E-9} secs")
      println(" ")
      rdd
    }

    
    def randomFeatureMap(rdd: RDD[(Int, (Int, Array[Double]))], numFeatures: Int) : 
    Tuple2[RDD[IndexedRow], RDD[(Int, Int)]]= {

        val targetRdd: RDD[(Int, Int)] = rdd.mapValues(pair => pair._1).persist()

        // rdd2 contains just the features
        val rdd2: RDD[(Int, Array[Double])] = rdd.mapValues(pair => pair._2).persist()

        // estimate the kernel parameter (if it is unknown)
        var sigma: Double = rdd2.map(pair => (pair._1.toDouble, pair._2)).glom.map(Kernel.estimateSigma).mean
        sigma = math.sqrt(sigma)
        println(s"Estimated sigma is ${sigma}")

        // random feature mapping
        val t1 = System.nanoTime()
        val rfmRdd : RDD[IndexedRow] = rdd2.map(pair => (pair._1.toDouble, pair._2))
                                                        .mapPartitions(Kernel.rbfRfm(_, numFeatures, sigma))
                                                        .map(pair => new IndexedRow(pair._1.toInt, new DenseVector(pair._2)))
                                                        .cache()
        rdd2.unpersist()
        val numfeats = rfmRdd.take(1)(0).vector.size
        println(s"numfeats = ${numfeats}")
        var t2 = System.nanoTime()
        println(s"Computing random feature mapping took ${(t2-t1)*1.0E-9} seconds")
        println(" ")
        (rfmRdd, targetRdd)
    }

    def oneHotEncode(target: Int, numClass: Int) : Array[Double] = {
        val encoding : Array[Double] = new Array[Double](numClass)
        encoding(target) = 1
        encoding
    }

    // arbitrarily indices the rows in an rdd from zero
    def RDDToIndexedRDD[T]( inRdd: RDD[T] ) : RDD[(Int, T)] = {
        val t1 = System.nanoTime()
        val rand = new scala.util.Random()
        val seedBase = rand.nextInt()
        val prioritizedElements = inRdd.mapPartitionsWithIndex(
            (index, iterator) => {
                val rand = new scala.util.Random(index + seedBase)
                iterator.map(x => (rand.nextDouble, x))
            }, true
        ).persist
        val sortedPriorities = prioritizedElements.map(pair => pair._1).collect().sorted.zipWithIndex.toMap
        val indexedRdd = prioritizedElements.map(pair => (sortedPriorities(pair._1), pair._2))
        val t2 = System.nanoTime()
        println(s"Converting RDD to an Indexed RDD took ${(t2 - t1)*1.0E-9} seconds")
        println(" ")
        indexedRdd.persist
    }

}



