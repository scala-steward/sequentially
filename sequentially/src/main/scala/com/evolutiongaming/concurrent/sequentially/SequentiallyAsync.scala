package com.evolutiongaming.concurrent.sequentially

import akka.stream.scaladsl.{Sink, Source, SourceQueue}
import akka.stream.{Materializer, OverflowStrategy}
import com.evolutiongaming.concurrent.FutureHelper.*
import com.evolutiongaming.concurrent.sequentially.Sequentially.{BufferSize, Substreams}
import com.evolutiongaming.concurrent.sequentially.SourceQueueHelper.*

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** Runs tasks sequentially for the same key and in parallel - for different keys */
trait SequentiallyAsync[-K] extends Sequentially[K] {

  def async[KK <: K, T](key: K)(task: => Future[T]): Future[T]

  def apply[KK <: K, T](key: KK)(task: => T): Future[T] = {
    async(key) {
      task.future
    }
  }
}

object SequentiallyAsync {

  def apply[K](
    substreams: Int = Substreams,
    bufferSize: Int = BufferSize,
    overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
  )(implicit
    materializer: Materializer
  ): SequentiallyAsync[K] = {

    require(substreams > 0, s"substreams is $substreams")
    require(bufferSize > 0, s"bufferSize is $bufferSize")

    val queue = Source
      .queue[Elem](bufferSize, overflowStrategy)
      .groupBy(substreams, _.substream)
      .buffer(bufferSize, OverflowStrategy.backpressure)
      .mapAsync(1) { _.apply() }
      .to(Sink.ignore)
      .run()

    implicit val ec: ExecutionContext = materializer.executionContext

    apply(substreams, queue)
  }

  def apply[K](
    substreams: Int,
    queue: SourceQueue[Elem],
  )(implicit
    ec: ExecutionContext
  ): SequentiallyAsync[K] = {

    val pf: PartialFunction[Throwable, Unit] = { case _ => () }

    new SequentiallyAsync[K] {

      def async[KK <: K, T](key: K)(task: => Future[T]): Future[T] = {
        val promise = Promise[T]()
        val safeTask = () => {
          val result = Future(task).flatten
          promise.completeWith(result)
          result.recover[Any](pf)
        }
        val substream = Substream(key, substreams)
        val elem = Elem(substream, safeTask)
        for {
          _ <- queue.offerOrError(elem, s"$key failed to enqueue task")
          result <- promise.future
        } yield result
      }
    }
  }

  def now[K]: SequentiallyAsync[K] = Now

  private object Now extends SequentiallyAsync[Any] {
    def async[KK <: Any, T](key: Any)(task: => Future[T]): Future[T] = {
      try task
      catch { case NonFatal(failure) => Future.failed(failure) }
    }
  }

  final case class Elem(substream: Int, apply: () => Future[Any])
}
