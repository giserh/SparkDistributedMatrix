package edu.purdue.dblab.apps

import edu.purdue.dblab.matrix.{ColumnPartitioner, RowPartitioner, Entry, BlockPartitionMatrix}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, SparkConf}
/**
  * Created by yongyangyu on 9/19/16.
  * This app executes BFGS computation on the Netflix dataset.
  * f(W, H) = ||V - WH||_F + ||W||_F + ||H||_F
  * the derivative of W, df(W, H)/dW = -2(V - WH)H.T + 2W,
  * and the derivative of H, df(W, H)/dH = -2W.T(V - WH) + 2H.
  * To leverage BFGS, we need to stack the derivatives in a long vector.
  */
object BFGSNetflix {
  def main(args: Array[String]): Unit = {
      if (args.length < 4) {
        println("Usage: BFGSNetflix <matrix> <nrows> <ncols> <ntopics> [<niter>]")
        System.exit(1)
      }
      val hdfs = "hdfs://10.100.121.126:8022/"
      val matrixName = hdfs + args(0)
      val (nrows, ncols) = (args(1).toLong, args(2).toLong)
      val ntopics = args(3).toInt
      var niter = 0
      if (args.length > 4) niter = args(4).toInt else niter = 10
      val conf = new SparkConf()
                    .setAppName("BFGSNetflix for GNMF")
                    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                    .set("spark.shuffle.consolidateFiles", "true")
                    .set("spark.shuffle.compress", "false")
                    .set("spark.cores.max", "80")
                    .set("spark.executor.memory", "48g")
                    .set("spark.default.parallelism", "100")
                    .set("spark.akka.frameSize", "1024")
      conf.setJars(SparkContext.jarOfClass(this.getClass).toArray)
      val sc = new SparkContext(conf)
      val blkSize = BlockPartitionMatrix.estimateBlockSizeWithDim(nrows, ncols)
      val V = BlockPartitionMatrix.createFromCoordinateEntries(genCOORdd(sc,
        matrixName), blkSize, blkSize, nrows, ncols).cache()
      val W1 = BlockPartitionMatrix.randMatrix(sc, nrows, ntopics, blkSize).partitionBy(new RowPartitioner(64))
      val H1 = BlockPartitionMatrix.randMatrix(sc, ntopics, ncols, blkSize).partitionBy(new ColumnPartitioner(64))
      val gradient1 = computeGradient(V, W1, H1)
      val W2 = BlockPartitionMatrix.randMatrix(sc, nrows, ntopics, blkSize).partitionBy(new RowPartitioner(64))
      val H2 = BlockPartitionMatrix.randMatrix(sc, ntopics, ncols, blkSize).partitionBy(new ColumnPartitioner(64))
      val gradient2 = computeGradient(V, W2, H2)
      val s = gradient1 * (-1)
      val y = gradient2 + (gradient1 * (-1))
      var B = BlockPartitionMatrix.randMatrix(sc, gradient1.nRows(), gradient2.nRows(), blkSize)
      B = B + y %*% y.t + (B %*% s %*% s.t %*% B) * (-1)
      B.saveAsTextFile(hdfs + "tmp_result/gnmf/B")
      Thread.sleep(10000)
  }

  def genCOORdd(sc: SparkContext, matrixName: String): RDD[Entry] = {
      val lines = sc.textFile(matrixName, 8)
      lines.map { s =>
          val line = s.split("\\s+")
          Entry(line(0).toLong - 1, line(1).toLong - 1, line(2).toDouble)
      }
  }

  def computeGradient(V: BlockPartitionMatrix, W: BlockPartitionMatrix,
                        H: BlockPartitionMatrix): BlockPartitionMatrix = {
      val dW = ((V + ((W %*% H) * (-1))) %*% H.t) * (-2) + (W * 2)
      val dH = (W.t %*% (V + (W %*% H) * (-1))) * (-2) + (H * 2)
      val Wvec = dW.vec().cache()
      val Hvec = dH.vec().cache()
      val WSize = Wvec.blocks.map { case ((i, j), mat) => i}.max()
      val RDD = Hvec.blocks.map { case ((i, j), mat) => ((i+WSize, j), mat)}.union(Wvec.blocks)
      new BlockPartitionMatrix(RDD, V.ROWS_PER_BLK, V.COLS_PER_BLK, Wvec.nRows() + Hvec.nRows(), 1)
  }
}
