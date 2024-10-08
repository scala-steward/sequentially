package com.evolutiongaming.concurrent.sequentially

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import com.evolutiongaming.concurrent.AvailableProcessors
import com.evolutiongaming.concurrent.sequentially.SourceQueueHelper.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/** Runs tasks sequentially for the same key and in parallel - for different keys */
trait Sequentially[-K] {

  def apply[KK <: K, T](key: KK)(task: => T): Future[T]

  def comap[KK <: K, T](f: T => KK): Sequentially[T] = new Sequentially.Comap(f, this)
}

object Sequentially {

  val Substreams: Int = (AvailableProcessors() max 1) * 5
  val BufferSize: Int = Int.MaxValue
  val Timeout: FiniteDuration = 5.seconds

  def apply[K](factory: ActorRefFactory): Sequentially[K] = {
    apply(factory, None, Substreams)
  }

  def apply[K](factory: ActorRefFactory, name: Option[String]): Sequentially[K] = {
    apply(factory, name, Substreams)
  }

  def apply[K](
    factory: ActorRefFactory,
    name: Option[String],
    substreams: Int,
  ): Sequentially[K] = {
    apply(factory, name, substreams, Timeout)
  }

  def apply[K](
    factory: ActorRefFactory,
    name: Option[String],
    substreams: Int,
    timeout: FiniteDuration,
  ): Sequentially[K] = {

    require(substreams > 0, s"substreams is $substreams")

    case class Task(task: () => Unit)

    def actor() = new Actor {
      def receive: Receive = { case Task(task) => task() }
    }

    val promise = Promise[Map[Int, ActorRef]]()

    def supervisor(): Actor = new Actor {
      private val props = Props(actor())
      private val refs = for {
        substream <- 0 until substreams
      } yield {
        val ref = context.actorOf(props, name = substream.toString)
        (substream, ref)
      }
      promise.success(refs.toMap)

      def receive: Receive = PartialFunction.empty
    }

    val props = Props(supervisor())
    name map { name => factory.actorOf(props, name) } getOrElse factory.actorOf(props)

    val refs = Await.result(promise.future, timeout)

    new Sequentially[K] {
      def apply[KK <: K, T](key: KK)(task: => T): Future[T] = {
        val promise = Promise[T]()
        val safeTask: () => Unit = () => promise complete Try(task)
        val substream = Substream(key, substreams)
        val ref = refs(substream)
        ref.tell(Task(safeTask), ActorRef.noSender)
        promise.future
      }
    }
  }

  def apply[K](
    substreams: Int = Substreams,
    bufferSize: Int = BufferSize,
    overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
  )(implicit
    materializer: Materializer
  ): Sequentially[K] = {

    val queue = Source
      .queue[Elem](bufferSize, overflowStrategy)
      .groupBy(substreams, _.substream)
      .buffer(bufferSize, OverflowStrategy.backpressure)
      .mapAsync(1) { _.apply() }
      .to(Sink.ignore)
      .run()(materializer)

    implicit val ecNow: ExecutionContext = ExecutionContext.parasitic
    val ec = materializer.executionContext

    case class Elem(substream: Int, apply: () => Future[Any])

    new Sequentially[K] {
      def apply[KK <: K, T](key: KK)(task: => T): Future[T] = {
        val promise = Promise[T]()
        val safeTask = () => {
          val result = Future(task)(ec)
          promise completeWith result
          result.recover[Any] { case _ => () }
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

  def now[K]: Sequentially[K] = Now

  private object Now extends Sequentially[Any] {
    def apply[KK <: Any, T](key: KK)(task: => T): Future[T] = {
      Future fromTry Try(task)
    }
  }

  class Comap[A, B](tmp: A => B, sequentially: Sequentially[B]) extends Sequentially[A] {
    def apply[AA <: A, T](key: AA)(f: => T): Future[T] = sequentially(tmp(key))(f)
  }
}
