package amplab.alchemist
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.DenseVector
import scala.collection.JavaConverters._
import scala.util.Random
import java.io.{
    InputStream, OutputStream,
    DataInputStream => JDataInputStream,
    DataOutputStream => JDataOutputStream
}
import java.nio.{
    DoubleBuffer, ByteBuffer
}
import java.nio.charset.StandardCharsets

class DataInputStream(istream: InputStream) extends JDataInputStream(istream) {
  def readArrayLength(): Int = {
    val result = readLong()
    assert(result.toInt == result)
    return result.toInt
  }

  def readBuffer(): Array[Byte] = {
    val buf = new Array[Byte](readArrayLength())
    read(buf)
    buf
  }

  def readDoubleArray(): Array[Double] = {
    val bufLen = readArrayLength()
    assert(bufLen % 8 == 0);
    val buf = new Array[Double](bufLen / 8);
    for(i <- 0 until bufLen / 8) {
      buf(i) = readDouble()
    }
    return buf
  }

  def readString(): String = {
    new String(readBuffer(), StandardCharsets.US_ASCII)
  }
}

class DataOutputStream(ostream: OutputStream) extends JDataOutputStream(ostream) {
  def writeDoubleArray(buf: Array[Double]): Unit = {
    writeLong(buf.length * 8)
    buf.foreach(writeDouble)
  }
}

class ProtocolError extends Exception {
}

class WorkerId(val id: Int) extends Serializable {
}

class MatrixHandle(val id: Int) extends Serializable {
}

class WorkerClient(val hostname: String, val port: Int) {
  val sock = java.nio.channels.SocketChannel.open(new java.net.InetSocketAddress(hostname, port))

  private def sendMessage(outbuf: ByteBuffer): Unit = {
    outbuf.rewind()
    while(sock.write(outbuf) != 0) {
    }
  }

  def newMatrix_addRow(handle: MatrixHandle, rowIdx: Long, vals: Array[Double]) = {
    val outbuf = ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 * vals.length)
    outbuf.putInt(0x1)  // typeCode = addRow
    outbuf.putInt(handle.id)
    outbuf.putLong(rowIdx)
    outbuf.putLong(vals.length * 8)
    outbuf.asDoubleBuffer().put(vals)
    sendMessage(outbuf)
  }

  def newMatrix_partitionComplete(handle: MatrixHandle) = {
    val outbuf = ByteBuffer.allocate(4)
    outbuf.putInt(0x2)  // typeCode = partitionComplete
    sendMessage(outbuf)
  }

  def getIndexedRowMatrix_getRow(handle: MatrixHandle, rowIndex: Long, numCols: Int) : DenseVector = {
    val outbuf = ByteBuffer.allocate(4 + 4 + 8)
    outbuf.putInt(0x3) // typeCode = getRow
    outbuf.putInt(handle.id)
    outbuf.putLong(rowIndex)
    sendMessage(outbuf)

    val inbuf = ByteBuffer.allocate(8 + 8 * numCols)
    while(inbuf.hasRemaining()) {
      sock.read(inbuf)
    }
    inbuf.flip()
    assert(numCols * 8 == inbuf.getLong())
    val vec = new Array[Double](numCols)
    inbuf.asDoubleBuffer().get(vec)
    return new DenseVector(vec)
  }

  def getIndexedRowMatrix_partitionComplete(handle: MatrixHandle) = {
    val outbuf = ByteBuffer.allocate(4)
    println(s"Finished getting rows on worker")
    outbuf.putInt(0x4) // typeCode = doneGettingRows
    sendMessage(outbuf)
  }

  def close() = {
    sock.close()
  }
}

class WorkerInfo(val id: WorkerId, val hostname: String, val port: Int) extends Serializable {
  def newClient(): WorkerClient = {
    System.err.println(s"connecting to $hostname:$port")
    new WorkerClient(hostname, port)
  }
}

class DriverClient(val istream: InputStream, val ostream: OutputStream) {
  val input = new DataInputStream(istream)
  val output = new DataOutputStream(ostream)

  // handshake
  output.writeInt(0xABCD)
  output.writeInt(0x1)
  output.flush()
  System.err.println("Spark Driver: wrote handshake")
  if(input.readInt() != 0xDCBA || input.readInt() != 0x1) {
    throw new ProtocolError()
  }
  System.err.println("Spark Driver: got handshake")

  val workerCount = input.readInt()

  val workerIds: Array[WorkerId] =
    (0 until workerCount).map(new WorkerId(_)).toArray

  val workerInfo: Array[WorkerInfo] =
    workerIds.map(id => new WorkerInfo(id, input.readString, input.readInt))

  def shutdown(): Unit = {
    output.writeInt(0xFFFFFFFF)
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
  }

  def getTranspose(handle: MatrixHandle) : MatrixHandle = {
    output.writeInt(0x6) 
    output.writeInt(handle.id)
    output.flush()
    if (input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    val transposeHandle = new MatrixHandle(input.readInt())
    if (input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    System.err.println(s"got handle: ${transposeHandle.id}")
    transposeHandle
  }

  def truncatedSVD(mat: MatrixHandle, k: Int) : Tuple3[MatrixHandle, MatrixHandle, MatrixHandle] = {
    output.writeInt(0x8) 
    output.writeInt(mat.id)
    output.writeInt(k)
    output.flush()
  
    if (input.readInt() != 0x1) {
      throw new ProtocolError()
    }

    val UHandle = new MatrixHandle(input.readInt())
    val SHandle = new MatrixHandle(input.readInt())
    val VHandle = new MatrixHandle(input.readInt())

    return (UHandle, SHandle, VHandle)
  }

  def kMeans(mat: MatrixHandle, k : Int, maxIters : Int, threshold: Double) : Tuple6[MatrixHandle, MatrixHandle, Int, Double, Int, Int] = {
    output.writeInt(0x7)
    output.writeInt(mat.id)
    output.writeInt(k)
    output.writeInt(maxIters)
    output.writeDouble(threshold)
    output.flush()

    if (input.readInt() != 0x1) {
      throw new ProtocolError() 
    }
    val assignmentsHandle = new MatrixHandle(input.readInt())
    val centersHandle = new MatrixHandle(input.readInt())
    val numIters = input.readInt()
    val percentageStable = input.readDouble()
    val restarts = input.readInt()
    val totalIters = input.readInt()

    return (centersHandle, assignmentsHandle, numIters, percentageStable, restarts, totalIters)
  }

  def matrixSVDStart(mat: MatrixHandle) : Tuple3[MatrixHandle, MatrixHandle, MatrixHandle] = {
    output.writeInt(0x5)
    output.writeInt(mat.id)
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    val Uhandle = new MatrixHandle(input.readInt())
    val Shandle = new MatrixHandle(input.readInt())
    val Vhandle = new MatrixHandle(input.readInt())
    System.err.println(s"got handles: ${Uhandle.id}, ${Shandle.id}, ${Vhandle.id}")
    return (Uhandle, Shandle, Vhandle)
  }

  // layout contains one worker ID per matrix partition
  def newMatrixStart(rows: Long, cols: Long, layout: Array[WorkerId]): MatrixHandle = {
    output.writeInt(0x1)
    output.writeLong(rows)
    output.writeLong(cols)
    output.writeLong(layout.length)
    layout.map(w => output.writeInt(w.id))
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    val handle = new MatrixHandle(input.readInt())
    System.err.println(s"got handle: ${handle.id}")
    return handle
  }

  def matrixMulStart(matA: MatrixHandle, matB: MatrixHandle) : MatrixHandle = {
    output.writeInt(0x2)
    output.writeInt(matA.id)
    output.writeInt(matB.id)
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    val handle = new MatrixHandle(input.readInt())
    System.err.println(s"got handle: ${handle.id}")
    return handle
  }

  def matrixMulFinish(mat: MatrixHandle) = {
    if(input.readInt() != 0x1) {
      throw new ProtocolError() 
    } 
  }

  def matrixSVDFinish(mat: MatrixHandle) = {
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    } 
    System.err.println(s"Finished computing the SVD for handle ${mat.id} successfully")
  }

  def newMatrixFinish(mat: MatrixHandle) = {
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
  }

  def getMatrixDimensions(mat: MatrixHandle) : Tuple2[Long, Int] = {
    output.writeInt(0x3)
    output.writeInt(mat.id)
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
    return (input.readLong(), input.readLong().toInt)
  }

  // layout maps each partition to a worker id (so has length number of partitions in the spark matrix being retrieved)
  def getIndexedRowMatrixStart(mat: MatrixHandle, layout: Array[WorkerId]) = {
    output.writeInt(0x4)
    output.writeInt(mat.id)
    output.writeLong(layout.length)
    layout.map(w => output.writeInt(w.id))
    output.flush()
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
  }

  def getIndexedRowMatrixFinish(mat: MatrixHandle) = {
    if(input.readInt() != 0x1) {
      throw new ProtocolError()
    }
  }

}

class Driver {
  val driverProc: Process = {
    val pb = {
      if(System.getenv("NERSC_HOST") != null) {
        val hostfilePath = s"${System.getenv("SPARK_WORKER_DIR")}/slaves.alchemist"
        val rootpath = "/global/cscratch1/sd/jeyk/alchemist/alchemist"
        val errpath = s"${System.getenv("SPARK_WORKER_DIR")}/alchemist-%j-%s-%t-%N.log"
        new ProcessBuilder("srun", "--input=0", "--output=0", "--error", errpath, "-Q", "-O", "-I30", "-N", "2", "-w", hostfilePath, s"${rootpath}/core/target/alchemist")
      } else {
        new ProcessBuilder("mpirun", "-q", "-np", "4", "core/target/alchemist")
      }
    }
    pb.redirectError(ProcessBuilder.Redirect.INHERIT).start
  }

  val client = new DriverClient(driverProc.getInputStream, driverProc.getOutputStream)

  def stop(): Unit = {
    client.shutdown
    driverProc.waitFor
  }
}

class AlContext(driver: DriverClient) extends Serializable {
  val workerIds: Array[WorkerId] = driver.workerIds
  val workerInfo: Array[WorkerInfo] = driver.workerInfo

  def connectWorker(worker: WorkerId): WorkerClient = {
    workerInfo(worker.id).newClient
  }
}

class Alchemist(val mysc: SparkContext) {
  System.err.println("launching alchemist")
  val sc : SparkContext = mysc

  val driver = new Driver()
  val client = driver.client

  // Instances of `Alchemist` are not serializable, but `.context`
  // has everything needed for RDD operations and is serializable.
  val context = new AlContext(client)

  def stop(): Unit = {
    driver.stop
  }

  def matMul(matA: AlMatrix, matB: AlMatrix) : AlMatrix = {
    val handle = client.matrixMulStart(matA.handle, matB.handle)
    client.matrixMulFinish(handle)
    new AlMatrix(this, handle)
  }

  def thinSVD(mat: AlMatrix) : Tuple3[AlMatrix, AlMatrix, AlMatrix] = {
    val (handleU, handleS, handleV) = client.matrixSVDStart(mat.handle)

    client.matrixSVDFinish(mat.handle)
    val Umat = new AlMatrix(this, handleU)
    val Smat = new AlMatrix(this, handleS)
    val Vmat = new AlMatrix(this, handleV)
    (Umat, Smat, Vmat)
  }

  def kMeans(mat: AlMatrix, k: Int, maxIters: Int, threshold: Double) : Tuple6[AlMatrix, AlMatrix, Int, Double, Int, Int] = {
    val (handleCenters, handleAssignments, numIters, stablePercentage, restarts, totalIters) = client.kMeans(mat.handle, k, maxIters, threshold)
    (new AlMatrix(this, handleCenters), new AlMatrix(this, handleAssignments), numIters, stablePercentage, restarts, totalIters)
  }

  def truncatedSVD(mat: AlMatrix, k: Int) : Tuple3[AlMatrix, AlMatrix, AlMatrix] = {
    val (uHandle, sHandle, vHandle) = client.truncatedSVD(mat.handle, k)
    (new AlMatrix(this, uHandle), new AlMatrix(this, sHandle), new AlMatrix(this, vHandle))
  }

}
