package com.datastax.spark.connector.rdd

import com.datastax.driver.core.{ResultSet, Session}
import com.datastax.spark.connector._
import com.datastax.spark.connector.cql._
import com.datastax.spark.connector.rdd.reader._
import com.datastax.spark.connector.writer._
import com.google.common.util.concurrent.{FutureCallback, Futures, SettableFuture}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
 * An [[org.apache.spark.rdd.RDD RDD]] that will do a selecting join between `left` RDD and the specified
 * Cassandra Table This will perform individual selects to retrieve the rows from Cassandra and will take
 * advantage of RDDs that have been partitioned with the
 * [[com.datastax.spark.connector.rdd.partitioner.ReplicaPartitioner]]
 *
 * @tparam L item type on the left side of the join (any RDD)
 * @tparam R item type on the right side of the join (fetched from Cassandra)
 */
class CassandraJoinRDD[L, R] private[connector](
    override val left: RDD[L],
    val keyspaceName: String,
    val tableName: String,
    val connector: CassandraConnector,
    val columnNames: ColumnSelector = AllColumns,
    val joinColumns: ColumnSelector = PartitionKeyColumns,
    val where: CqlWhereClause = CqlWhereClause.empty,
    val limit: Option[CassandraLimit] = None,
    val clusteringOrder: Option[ClusteringOrder] = None,
    val readConf: ReadConf = ReadConf(),
    manualRowReader: Option[RowReader[R]] = None,
    override val manualRowWriter: Option[RowWriter[L]] = None)(
  implicit
    val leftClassTag: ClassTag[L],
    val rightClassTag: ClassTag[R],
    @transient val rowWriterFactory: RowWriterFactory[L],
    @transient val rowReaderFactory: RowReaderFactory[R])
  extends CassandraRDD[(L, R)](left.sparkContext, left.dependencies)
  with CassandraTableRowReaderProvider[R]
  with AbstractCassandraJoin[L, R] {

  override type Self = CassandraJoinRDD[L, R]

  override protected val classTag = rightClassTag

  override lazy val rowReader: RowReader[R] = manualRowReader match {
    case Some(rr) => rr
    case None => rowReaderFactory.rowReader(tableDef, columnNames.selectFrom(tableDef))
  }

  override protected def copy(
    columnNames: ColumnSelector = columnNames,
    where: CqlWhereClause = where,
    limit: Option[CassandraLimit] = limit,
    clusteringOrder: Option[ClusteringOrder] = None,
    readConf: ReadConf = readConf,
    connector: CassandraConnector = connector
  ): Self = {

    new CassandraJoinRDD[L, R](
      left = left,
      keyspaceName = keyspaceName,
      tableName = tableName,
      connector = connector,
      columnNames = columnNames,
      joinColumns = joinColumns,
      where = where,
      limit = limit,
      clusteringOrder = clusteringOrder,
      readConf = readConf
    )
  }

  override def cassandraCount(): Long = {
    columnNames match {
      case SomeColumns(_) =>
        logWarning("You are about to count rows but an explicit projection has been specified.")
      case _ =>
    }

    val counts =
      new CassandraJoinRDD[L, Long](
        left = left,
        connector = connector,
        keyspaceName = keyspaceName,
        tableName = tableName,
        columnNames = SomeColumns(RowCountRef),
        joinColumns = joinColumns,
        where = where,
        limit = limit,
        clusteringOrder = clusteringOrder,
        readConf = readConf
      )

    counts.map(_._2).reduce(_ + _)
  }

  def on(joinColumns: ColumnSelector): CassandraJoinRDD[L, R] = {
    new CassandraJoinRDD[L, R](
      left = left,
      connector = connector,
      keyspaceName = keyspaceName,
      tableName = tableName,
      columnNames = columnNames,
      joinColumns = joinColumns,
      where = where,
      limit = limit,
      clusteringOrder = clusteringOrder,
      readConf = readConf
    )
  }

  private[rdd] def fetchIterator(
    session: Session,
    bsb: BoundStatementBuilder[L],
    leftIterator: Iterator[L]
  ): Iterator[(L, R)] = {
    val columnNames = selectedColumnRefs.map(_.selectedAs).toIndexedSeq
    val rateLimiter = new RateLimiter(
      readConf.readsPerSec, readConf.readsPerSec
    )

    val queryExecutor = QueryExecutor(session, None, None)

    def pairWithRight(left: L): SettableFuture[Iterator[(L, R)]] = {
      val resultFuture = SettableFuture.create[Iterator[(L, R)]]
      val leftSide = Iterator.continually(left)

      val queryFuture = queryExecutor.executeAsync(bsb.bind(left))
      Futures.addCallback(queryFuture, new FutureCallback[ResultSet] {
        def onSuccess(rs: ResultSet) {
          val resultSet = new PrefetchingResultSetIterator(rs, fetchSize)
          val columnMetaData = CassandraRowMetadata.fromResultSet(columnNames, rs);
          val rightSide = resultSet.map(rowReader.read(_, columnMetaData))
          resultFuture.set(leftSide.zip(rightSide))
        }
        def onFailure(throwable: Throwable) {
          resultFuture.setException(throwable)
        }
      })
      resultFuture
    }
    val queryFutures = leftIterator.map(left => {
      rateLimiter.maybeSleep(1)
      pairWithRight(left)
    }).toList
    queryFutures.iterator.flatMap(_.get)
  }

  /**
   * Turns this CassandraJoinRDD into a factory for converting other RDD's after being serialized
   * This method is for streaming operations as it allows us to Serialize a template JoinRDD
   * and the use that serializable template in the DStream closure. This gives us a fully serializable
   * joinWithCassandra operation
   */
  private[connector] def applyToRDD(left: RDD[L]): CassandraJoinRDD[L, R] = {
    new CassandraJoinRDD[L, R](
      left,
      keyspaceName,
      tableName,
      connector,
      columnNames,
      joinColumns,
      where,
      limit,
      clusteringOrder,
      readConf,
      Some(rowReader),
      Some(rowWriter)
    )
  }
}
