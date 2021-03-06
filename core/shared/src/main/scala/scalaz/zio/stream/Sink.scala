package scalaz.zio.stream

import scalaz.zio._
import scala.language.postfixOps

/**
 * A `Sink[E, A0, A, B]` consumes values of type `A`, ultimately producing
 * either an error of type `E`, or a value of type `B` together with a remainder
 * of type `A0`.
 *
 * Sinks form monads and combine in the usual ways.
 */
trait Sink[+E, +A0, -A, +B] { self =>
  import Sink.Step

  type State

  def initial: IO[E, Step[State, Nothing]]

  def step(state: State, a: A): IO[E, Step[State, A0]]

  def extract(state: State): IO[E, B]

  def stepChunk[A1 <: A](state: State, as: Chunk[A1]): IO[E, Step[State, A0]] = {
    val len = as.length

    def loop(s: Step[State, A0], i: Int): IO[E, Step[State, A0]] =
      if (i >= len) IO.succeed(s)
      else if (Step.cont(s)) self.step(Step.state(s), as(i)).flatMap(loop(_, i + 1))
      else IO.succeed(s)

    loop(Step.more(state), 0)
  }

  final def update(state: Step[State, Nothing]): Sink[E, A0, A, B] =
    new Sink[E, A0, A, B] {
      type State = self.State
      val initial: IO[E, Step[State, Nothing]]             = IO.succeed(state)
      def step(state: State, a: A): IO[E, Step[State, A0]] = self.step(state, a)
      def extract(state: State): IO[E, B]                  = self.extract(state)
    }

  /**
   * Takes a `Sink`, and lifts it to be chunked in its input and output. This
   * will not improve performance, but can be used to adapt non-chunked sinks
   * wherever chunked sinks are required.
   */
  final def chunked[A1 >: A0, A2 <: A]: Sink[E, A1, Chunk[A2], B] =
    new Sink[E, A1, Chunk[A2], B] {
      type State = self.State
      val initial = self.initial
      def step(state: State, a: Chunk[A2]): IO[E, Step[State, A1]] =
        self.stepChunk(state, a)
      def extract(state: State): IO[E, B] = self.extract(state)
    }

  /**
   * Effectfully maps the value produced by this sink.
   */
  final def mapM[E1 >: E, C](f: B => IO[E1, C]): Sink[E1, A0, A, C] =
    new Sink[E1, A0, A, C] {
      type State = self.State

      val initial: IO[E, Step[State, Nothing]] = self.initial

      def step(state: State, a: A): IO[E, Step[State, A0]] = self.step(state, a)

      def extract(state: State): IO[E1, C] = self.extract(state).flatMap(f)
    }

  /**
   * Maps the value produced by this sink.
   */
  def map[C](f: B => C): Sink[E, A0, A, C] =
    new Sink[E, A0, A, C] {
      type State = self.State

      val initial: IO[E, Step[State, Nothing]] = self.initial

      def step(state: State, a: A): IO[E, Step[State, A0]] = self.step(state, a)

      def extract(state: State): IO[E, C] = self.extract(state).map(f)
    }

  /**
   * Effectfully filters the inputs fed to this sink.
   */
  final def filterM[E1 >: E, A1 <: A](f: A1 => IO[E1, Boolean]): Sink[E1, A0, A1, B] =
    new Sink[E1, A0, A1, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, a: A1) =
        f(a).flatMap(
          b =>
            if (b) self.step(state, a)
            else IO.succeed(Step.more(state))
        )

      def extract(state: State) = self.extract(state)
    }

  /**
   * Filters the inputs fed to this sink.
   */
  def filter[A1 <: A](f: A1 => Boolean): Sink[E, A0, A1, B] =
    new Sink[E, A0, A1, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, a: A1) =
        if (f(a)) self.step(state, a)
        else IO.succeed(Step.more(state))

      def extract(state: State) = self.extract(state)
    }

  final def filterNot[A1 <: A](f: A1 => Boolean): Sink[E, A0, A1, B] =
    filter(a => !f(a))

  final def filterNotM[E1 >: E, A1 <: A](f: A1 => IO[E1, Boolean]): Sink[E1, A0, A1, B] =
    filterM(a => f(a).map(!_))

  final def contramapM[E1 >: E, C](f: C => IO[E1, A]): Sink[E1, A0, C, B] =
    new Sink[E1, A0, C, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, c: C) = f(c).flatMap(self.step(state, _))

      def extract(state: State): IO[E1, B] = self.extract(state)
    }

  def contramap[C](f: C => A): Sink[E, A0, C, B] =
    new Sink[E, A0, C, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, c: C) = self.step(state, f(c))

      def extract(state: State): IO[E, B] = self.extract(state)
    }

  def dimap[C, D](f: C => A)(g: B => D): Sink[E, A0, C, D] =
    new Sink[E, A0, C, D] {
      type State = self.State

      val initial = self.initial

      def step(state: State, c: C): IO[E, Step[State, A0]] = self.step(state, f(c))

      def extract(state: State): IO[E, D] = self.extract(state).map(g)
    }

  def mapError[E1](f: E => E1): Sink[E1, A0, A, B] =
    new Sink[E1, A0, A, B] {
      type State = self.State

      val initial = self.initial.leftMap(f)

      def step(state: State, a: A): IO[E1, Step[State, A0]] =
        self.step(state, a).leftMap(f)

      def extract(state: State): IO[E1, B] =
        self.extract(state).leftMap(f)
    }

  def mapRemainder[A1](f: A0 => A1): Sink[E, A1, A, B] =
    new Sink[E, A1, A, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, a: A): IO[E, Step[State, A1]] =
        self.step(state, a).map(Step.map(_)(f))

      def extract(state: State): IO[E, B] = self.extract(state)
    }

  final def const[C](c: => C): Sink[E, A0, A, C] = self.map(_ => c)

  final def void: Sink[E, A0, A, Unit] = const(())

  final def untilOutput(f: B => Boolean): Sink[E, A0, A, B] =
    new Sink[E, A0, A, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, a: A): IO[E, Step[State, A0]] =
        self.step(state, a).flatMap { s =>
          if (Step.cont(s))
            extract(Step.state(s))
              .redeem(
                _ => IO.succeed(s),
                b => if (f(b)) IO.succeed(Step.done(Step.state(s), Chunk.empty)) else IO.succeed(s)
              )
          else IO.succeed(s)
        }

      def extract(state: State): IO[E, B] = self.extract(state)
    }

  /**
   * Returns a new sink that tries to produce the `B`, but if there is an
   * error in stepping or extraction, produces `None`.
   */
  final def ? : Sink[Nothing, A0, A, Option[B]] =
    new Sink[Nothing, A0, A, Option[B]] {
      type State = Option[self.State]

      val initial = self.initial.map(Step.leftMap(_)(Some(_))) orElse
        IO.succeed(Step.done(None, Chunk.empty))

      def step(state: State, a: A): IO[Nothing, Step[State, A0]] =
        state match {
          case None => IO.succeed(Step.done(state, Chunk.empty))
          case Some(state) =>
            self
              .step(state, a)
              .redeem(
                _ => IO.succeed(Step.done[State, A0](Some(state), Chunk.empty)),
                s => IO.succeed(Step.leftMap(s)(Some(_)))
              )
        }

      def extract(state: State): IO[Nothing, Option[B]] =
        state match {
          case None        => IO.succeed(None)
          case Some(state) => self.extract(state).map(Some(_)) orElse IO.succeed(None)
        }
    }

  /**
   * A named alias for `?`.
   */
  final def optional: Sink[Nothing, A0, A, Option[B]] = self ?

  /**
   * Runs both sinks in parallel on the input, returning the result from the
   * one that finishes successfully first.
   */
  final def race[E1 >: E, A2 >: A0, A1 <: A, B1 >: B](that: Sink[E1, A2, A1, B1]): Sink[E1, A2, A1, B1] =
    self.raceBoth(that).map(_.merge)

  /**
   * A named alias for `race`.
   */
  final def |[E1 >: E, A2 >: A0, A1 <: A, B1 >: B](that: Sink[E1, A2, A1, B1]): Sink[E1, A2, A1, B1] =
    self.race(that)

  /**
   * Runs both sinks in parallel on the input, returning the result from the
   * one that finishes successfully first.
   */
  final def raceBoth[E1 >: E, A2 >: A0, A1 <: A, C](that: Sink[E1, A2, A1, C]): Sink[E1, A2, A1, Either[B, C]] =
    new Sink[E1, A2, A1, Either[B, C]] {
      type State = (Either[E1, self.State], Either[E1, that.State])

      def sequence[E, S, A0](e: Either[E, Step[S, A0]]): Step[Either[E, S], A0] =
        e match {
          case Left(e)                   => Step.done(Left(e), Chunk.empty)
          case Right(s) if Step.cont(s)  => Step.more(Right(Step.state(s)))
          case Right(s) if !Step.cont(s) => Step.done(Right(Step.state(s)), Step.leftover(s))
        }

      val initial = self.initial.attempt.map(sequence).zipWithPar(that.initial.attempt.map(sequence))(Step.both)

      def step(state: State, a: A1): IO[E1, Step[State, A2]] =
        state match {
          case (l, r) =>
            val lr = l.fold(e => IO.succeed(Left(e)), self.step(_, a).attempt)
            val rr = r.fold(e => IO.succeed(Left(e)), that.step(_, a).attempt)

            lr.zipWithPar(rr) {
              case (Right(s), _) if !Step.cont(s) => Step.done((Right(Step.state(s)), r), Step.leftover(s))
              case (_, Right(s)) if !Step.cont(s) => Step.done((l, Right(Step.state(s))), Step.leftover(s))
              case (lr, rr)                       => Step.more((lr.right.map(Step.state), rr.right.map(Step.state)))
            }
        }

      def extract(state: State): IO[E1, Either[B, C]] =
        state match {
          case (Right(s), _) => self.extract(s).map(Left(_))
          case (_, Right(s)) => that.extract(s).map(Right(_))
          case (Left(e), _)  => IO.fail(e)
          case (_, Left(e))  => IO.fail(e)
        }
    }

  final def takeWhile[A1 <: A](pred: A1 => Boolean): Sink[E, A0, A1, B] =
    new Sink[E, A0, A1, B] {
      type State = self.State

      val initial = self.initial

      def step(state: State, a: A1): IO[E, Step[State, A0]] =
        if (pred(a)) self.step(state, a)
        else IO.succeed(Step.done(state, Chunk.empty))

      def extract(state: State) = self.extract(state)
    }

  final def dropWhile[A1 <: A](pred: A1 => Boolean): Sink[E, A0, A1, B] =
    new Sink[E, A0, A1, B] {
      type State = (self.State, Boolean)

      val initial = self.initial.map(step => Step.leftMap(step)((_, true)))

      def step(state: State, a: A1): IO[E, Step[State, A0]] =
        if (!state._2) self.step(state._1, a).map(Step.leftMap(_)((_, false)))
        else {
          if (pred(a)) IO.succeed(Sink.Step.more((state)))
          else self.step(state._1, a).map(Step.leftMap(_)((_, false)))
        }

      def extract(state: State) = self.extract(state._1)
    }
}

object Sink {
  private[Sink] object internal {
    sealed trait Side[+E, +S, +A]
    object Side {
      final case class Error[E](value: E) extends Side[E, Nothing, Nothing]
      final case class State[S](value: S) extends Side[Nothing, S, Nothing]
      final case class Value[A](value: A) extends Side[Nothing, Nothing, A]
    }
  }

  trait StepModule {
    type Step[+S, +A0]

    def state[S, A0](s: Step[S, A0]): S
    def leftover[S, A0](s: Step[S, A0]): Chunk[A0]
    def cont[S, A0](s: Step[S, A0]): Boolean

    def more[S](s: S): Step[S, Nothing]
    def done[@specialized S, A0](s: S, a0: Chunk[A0]): Step[S, A0]

    def map[S, A0, B](s: Step[S, A0])(f: A0 => B): Step[S, B]
    def leftMap[S, A0, B](s: Step[S, A0])(f: S => B): Step[B, A0]
    def bimap[S, S1, A, B](s: Step[S, A])(f: S => S1, g: A => B): Step[S1, B]
    def both[S1, S2](s1: Step[S1, Nothing], s2: Step[S2, Nothing]): Step[(S1, S2), Nothing]
    def either[S1, A0](s1: Step[S1, A0], s2: Step[S1, A0]): Step[S1, A0]
  }

  private class Done[@specialized S, A0](val s: S, val leftover: Chunk[A0])

  val Step: StepModule = new StepModule {
    type Step[+S, +A0] = AnyRef

    final def state[S, A0](s: Step[S, A0]): S =
      s match {
        case x: Done[_, _] => x.s.asInstanceOf[S]
        case x             => x.asInstanceOf[S]
      }
    final def leftover[S, A0](s: Step[S, A0]): Chunk[A0] =
      s match {
        case x: Done[_, _] => x.leftover.asInstanceOf[Chunk[A0]]
        case _             => Chunk.empty
      }
    final def cont[S, A0](s: Step[S, A0]): Boolean =
      !s.isInstanceOf[Done[_, _]]

    final def more[S](s: S): Step[S, Nothing] = s.asInstanceOf[Step[S, Nothing]]
    final def done[@specialized S, A0](s: S, a0: Chunk[A0]): Step[S, A0] =
      new Done(s, a0)

    final def map[S, A, B](s: Step[S, A])(f: A => B): Step[S, B] =
      s match {
        case x: Done[_, _] => new Done(x.s.asInstanceOf[S], x.leftover.asInstanceOf[Chunk[A]].map(f))
        case x             => x.asInstanceOf[Step[S, B]]
      }

    final def leftMap[S, A0, B](s: Step[S, A0])(f: S => B): Step[B, A0] =
      s match {
        case x: Done[_, _] => new Done(f(x.s.asInstanceOf[S]), x.leftover)
        case x             => f(x.asInstanceOf[S]).asInstanceOf[Step[B, A0]]
      }

    final def bimap[S, S1, A, B](s: Step[S, A])(f: S => S1, g: A => B): Step[S1, B] =
      s match {
        case x: Done[_, _] => new Done(f(x.s.asInstanceOf[S]), x.leftover.asInstanceOf[Chunk[A]].map(g))
        case x             => f(x.asInstanceOf[S]).asInstanceOf[Step[S1, B]]
      }

    final def both[S1, S2](s1: Step[S1, Nothing], s2: Step[S2, Nothing]): Step[(S1, S2), Nothing] =
      (s1, s2) match {
        case (x: Done[_, _], y: Done[_, _]) => done((state(x), state(y)), Chunk.empty)
        case (x, y: Done[_, _])             => done((state(x), state(y)), Chunk.empty)
        case (x: Done[_, _], y)             => done((state(x), state(y)), Chunk.empty)
        case (x, y)                         => more((state(x), state(y)))
      }

    final def either[S1, A0](s1: Step[S1, A0], s2: Step[S1, A0]): Step[S1, A0] =
      (s1, s2) match {
        case (x: Done[_, _], _: Done[_, _]) => done(state(x), Chunk.empty)
        case (x, _: Done[_, _])             => more(state(x))
        case (_: Done[_, _], y)             => more(state(y))
        case (x, _)                         => more(state(x))
      }
  }

  type Step[+S, +A0] = Step.Step[S, A0]

  final def more[E, A0, A, B](end: IO[E, B])(input: A => Sink[E, A0, A, B]): Sink[E, A0, A, B] =
    new Sink[E, A0, A, B] {
      type State = Option[(Sink[E, A0, A, B], Any)]

      val initial = IO.succeed(Step.more(None))

      def step(state: State, a: A): IO[E, Step[State, A0]] = state match {
        case None =>
          val sink = input(a)

          sink.initial.map(state => Step.more(Some((sink, state))))

        case Some((sink, state)) =>
          sink.step(state.asInstanceOf[sink.State], a).map(Step.leftMap(_)(state => Some(sink -> state)))
      }

      def extract(state: State): IO[E, B] = state match {
        case None                => end
        case Some((sink, state)) => sink.extract(state.asInstanceOf[sink.State])
      }
    }

  final def succeedLazy[B](b: => B): Sink[Nothing, Nothing, Any, B] =
    new SinkPure[Nothing, Nothing, Any, B] {
      type State = Unit
      val initialPure                                          = Step.done((), Chunk.empty)
      def stepPure(state: State, a: Any): Step[State, Nothing] = Step.done(state, Chunk.empty)
      def extractPure(state: State): Either[Nothing, B]        = Right(b)
    }

  final def drain: Sink[Nothing, Nothing, Any, Unit] =
    fold(())((s, _) => Step.more(s))

  final def collect[A]: Sink[Nothing, Nothing, A, List[A]] =
    fold[Nothing, A, List[A]](List.empty[A])((as, a) => Step.more(a :: as)).map(_.reverse)

  final def liftIO[E, B](b: => IO[E, B]): Sink[E, Nothing, Any, B] =
    new Sink[E, Nothing, Any, B] {
      type State = Unit
      val initial                                                 = IO.succeed(Step.done((), Chunk.empty))
      def step(state: State, a: Any): IO[E, Step[State, Nothing]] = IO.succeed(Step.done(state, Chunk.empty))
      def extract(state: State): IO[E, B]                         = b
    }

  final def lift[A, B](f: A => B): Sink[Unit, Nothing, A, B] =
    new SinkPure[Unit, Nothing, A, B] {
      type State = Option[A]
      val initialPure                                        = Step.more(None)
      def stepPure(state: State, a: A): Step[State, Nothing] = Step.done(Some(a), Chunk.empty)
      def extractPure(state: State): Either[Unit, B]         = state.fold[Either[Unit, B]](Left(()))(a => Right(f(a)))
    }

  final def identity[A]: Sink[Unit, A, A, A] =
    new SinkPure[Unit, A, A, A] {
      type State = Option[A]
      val initialPure                                  = Step.more(None)
      def stepPure(state: State, a: A): Step[State, A] = Step.done(Some(a), Chunk.empty)
      def extractPure(state: State): Either[Unit, A]   = state.fold[Either[Unit, A]](Left(()))(a => Right(a))
    }

  final def fail[E](e: E): Sink[E, Nothing, Any, Nothing] =
    new SinkPure[E, Nothing, Any, Nothing] {
      type State = Unit
      val initialPure                                          = Step.done((), Chunk.empty)
      def stepPure(state: State, a: Any): Step[State, Nothing] = Step.done(state, Chunk.empty)
      def extractPure(state: State): Either[E, Nothing]        = Left(e)
    }

  def fold[A0, A, S](z: S)(f: (S, A) => Step[S, A0]): Sink[Nothing, A0, A, S] =
    new SinkPure[Nothing, A0, A, S] {
      type State = S
      val initialPure                           = Step.more(z)
      def stepPure(s: S, a: A): Step[S, A0]     = f(s, a)
      def extractPure(s: S): Either[Nothing, S] = Right(s)
    }

  def foldLeft[A0, A, S](z: S)(f: (S, A) => S): Sink[Nothing, A0, A, S] =
    new SinkPure[Nothing, A0, A, S] {
      type State = S
      val initialPure                           = Step.more(z)
      def stepPure(s: S, a: A): Step[S, A0]     = Step.more(f(s, a))
      def extractPure(s: S): Either[Nothing, S] = Right(s)
    }

  def foldM[E, A0, A, S](z: IO[E, S])(f: (S, A) => IO[E, Step[S, A0]]): Sink[E, A0, A, S] =
    new Sink[E, A0, A, S] {
      type State = S
      val initial                              = z.map(Step.more)
      def step(s: S, a: A): IO[E, Step[S, A0]] = f(s, a)
      def extract(s: S): IO[E, S]              = IO.succeed(s)
    }

  def readWhileM[E, A](p: A => IO[E, Boolean]): Sink[E, A, A, List[A]] =
    Sink
      .foldM(IO.succeed(List.empty[A])) { (s, a: A) =>
        p(a).map(if (_) Step.more(a :: s) else Step.done(s, Chunk(a)))
      }
      .map(_.reverse)

  def readWhile[A](p: A => Boolean): Sink[Nothing, A, A, List[A]] =
    readWhileM(a => IO.succeed(p(a)))

  def ignoreWhileM[E, A](p: A => IO[E, Boolean]): Sink[E, A, A, Unit] =
    new Sink[E, A, A, Unit] {
      type State = Unit

      val initial = IO.succeed(Step.more(()))

      def step(state: State, a: A): IO[E, Step[State, A]] =
        p(a).map(if (_) Step.more(()) else Step.done((), Chunk(a)))

      def extract(state: State) = IO.succeed(())
    }

  def ignoreWhile[A](p: A => Boolean): Sink[Nothing, A, A, Unit] =
    ignoreWhileM(a => IO.succeed(p(a)))

  def await[A]: Sink[Unit, Nothing, A, A] =
    new SinkPure[Unit, Nothing, A, A] {
      type State = Either[Unit, A]

      val initialPure = Step.more(Left(()))

      def stepPure(state: State, a: A) =
        Step.done(Right(a), Chunk.empty)

      def extractPure(state: State): Either[Unit, A] =
        state
    }

  def read1[E, A](e: Option[A] => E)(p: A => Boolean): Sink[E, A, A, A] =
    new SinkPure[E, A, A, A] {
      type State = Either[E, Option[A]]

      val initialPure = Step.more(Right(None))

      def stepPure(state: State, a: A) =
        state match {
          case Right(Some(_)) => Step.done(state, Chunk(a))
          case Right(None) =>
            if (p(a)) Step.done(Right(Some(a)), Chunk.empty)
            else Step.done(Left(e(Some(a))), Chunk(a))
          case s => Step.done(s, Chunk(a))
        }

      def extractPure(state: State) =
        state match {
          case Right(Some(a)) => Right(a)
          case Right(None)    => Left(e(None))
          case Left(e)        => Left(e)
        }
    }

  implicit class InvariantOps[E, A0, A, B](self: Sink[E, A, A, B]) {
    final def <|[E1, B1 >: B](
      that: Sink[E1, A, A, B1]
    ): Sink[E1, A, A, B1] =
      (self orElse that).map(_.merge)

    /**
     * Runs both sinks in parallel on the same input. If the left one succeeds,
     * its value will be produced. Otherwise, whatever the right one produces
     * will be produced. If the right one succeeds before the left one, it
     * accumulates the full input until the left one fails, so it can return
     * it as the remainder. This allows this combinator to function like `choice`
     * in parser combinator libraries.
     *
     * Left:  ============== FAIL!
     * Right: ===== SUCCEEDS!
     *             xxxxxxxxx <- Should NOT be consumed
     */
    final def orElse[E1, C](
      that: Sink[E1, A, A, C]
    ): Sink[E1, A, A, Either[B, C]] =
      new Sink[E1, A, A, Either[B, C]] {
        import Sink.internal._

        def sequence[E, S, A0](e: Either[E, Step[S, A0]]): Step[Either[E, S], A0] =
          e match {
            case Left(e)                   => Step.done(Left(e), Chunk.empty)
            case Right(s) if Step.cont(s)  => Step.more(Right(Step.state(s)))
            case Right(s) if !Step.cont(s) => Step.done(Right(Step.state(s)), Step.leftover(s))
          }

        def eitherToSide[E, A, B](e: Either[E, A]): Side[E, A, B] =
          e match {
            case Left(e)  => Side.Error(e)
            case Right(s) => Side.State(s)
          }

        type State = (Side[E, self.State, B], Side[E1, that.State, (Chunk[A], C)])

        val l: IO[Nothing, Step[Either[E, self.State], Nothing]]  = self.initial.attempt.map(sequence)
        val r: IO[Nothing, Step[Either[E1, that.State], Nothing]] = that.initial.attempt.map(sequence)

        val initial: IO[E1, Step[State, Nothing]] =
          l.zipWithPar(r) { (l, r) =>
            Step.both(Step.leftMap(l)(eitherToSide), Step.leftMap(r)(eitherToSide))
          }

        def step(state: State, a: A): IO[E1, Step[State, A]] = {
          val leftStep: IO[Nothing, Step[Side[E, self.State, B], A]] =
            state._1 match {
              case Side.State(s) =>
                self
                  .step(s, a)
                  .redeem(
                    e => IO.succeed(Step.done(Side.Error(e), Chunk.empty)),
                    s =>
                      if (Step.cont(s)) IO.succeed(Step.more(Side.State(Step.state(s))))
                      else
                        self
                          .extract(Step.state(s))
                          .redeemPure(
                            e => Step.done(Side.Error(e), Step.leftover(s)),
                            b => Step.done(Side.Value(b), Step.leftover(s))
                          )
                  )
              case s => IO.succeed(Step.done(s, Chunk.empty))
            }
          val rightStep: IO[Nothing, Step[Side[E1, that.State, (Chunk[A], C)], A]] =
            state._2 match {
              case Side.State(s) =>
                that
                  .step(s, a)
                  .redeem(
                    e => IO.succeed(Step.done(Side.Error(e), Chunk.empty)),
                    s =>
                      if (Step.cont(s)) IO.succeed(Step.more(Side.State(Step.state(s))))
                      else {
                        that
                          .extract(Step.state(s))
                          .redeemPure(
                            e => Step.done(Side.Error(e), Step.leftover(s)),
                            c => Step.done(Side.Value((Step.leftover(s), c)), Step.leftover(s))
                          )
                      }
                  )
              case Side.Value((a0, c)) =>
                val a3 = a0 ++ Chunk(a)

                IO.succeed(Step.done(Side.Value((a3, c)), a3))

              case s => IO.succeed(Step.done(s, Chunk.empty))
            }

          leftStep.zip(rightStep).flatMap {
            case (s1, s2) =>
              if (Step.cont(s1) && Step.cont(s2))
                IO.succeed(Step.more((Step.state(s1), Step.state(s2))))
              else if (!Step.cont(s1) && !Step.cont(s2)) {
                // Step.Done(s1, a1), Step.Done(s2, a2)
                Step.state(s1) match {
                  case Side.Error(_) => IO.succeed(Step.done((Step.state(s1), Step.state(s2)), Step.leftover(s2)))
                  case Side.Value(_) => IO.succeed(Step.done((Step.state(s1), Step.state(s2)), Step.leftover(s1)))
                  case Side.State(s) =>
                    self
                      .extract(s)
                      .redeemPure(
                        e => Step.done((Side.Error(e), Step.state(s2)), Step.leftover(s2)),
                        b => Step.done((Side.Value(b), Step.state(s2)), Step.leftover(s1))
                      )
                }
              } else if (Step.cont(s1) && !Step.cont(s2)) IO.succeed(Step.more((Step.state(s1), Step.state(s2))))
              else {
                // Step.Done(s1, a1), Step.More(s2)
                Step.state(s1) match {
                  case Side.Error(_) => IO.succeed(Step.more((Step.state(s1), Step.state(s2))))
                  case Side.Value(_) => IO.succeed(Step.done((Step.state(s1), Step.state(s2)), Step.leftover(s1)))
                  case Side.State(s) =>
                    self.extract(s).redeemPure(Side.Error(_), Side.Value(_)).map(s1 => Step.more((s1, Step.state(s2))))
                }

              }
          }
        }

        def extract(state: State): IO[E1, Either[B, C]] = {
          def fromRight: IO[E1, Either[B, C]] = state._2 match {
            case Side.Value((_, c)) => IO.succeed(Right(c))
            case Side.State(s)      => that.extract(s).map(Right(_))
            case Side.Error(e)      => IO.fail(e)
          }

          state match {
            case (Side.Value(b), _) => IO.succeed(Left(b))
            case (Side.State(s), _) => self.extract(s).redeem(_ => fromRight, b => IO.succeed(Left(b)))
            case _                  => fromRight
          }
        }
      }

    final def flatMap[E1 >: E, C](
      f: B => Sink[E1, A, A, C]
    ): Sink[E1, A, A, C] =
      new Sink[E1, A, A, C] {
        type State = Either[self.State, (Sink[E1, A, A, C], Any)]

        val initial: IO[E1, Step[State, Nothing]] = self.initial.map(Step.leftMap(_)(Left(_)))

        def step(state: State, a: A): IO[E1, Step[State, A]] = state match {
          case Left(s1) =>
            self.step(s1, a) flatMap { s1 =>
              if (Step.cont(s1)) IO.succeed(Step.more(Left(Step.state(s1))))
              else {
                val as = Step.leftover(s1)

                self.extract(Step.state(s1)).flatMap { b =>
                  val that = f(b)

                  that.initial.flatMap(
                    s2 =>
                      that
                        .stepChunk(Step.state(s2).asInstanceOf[that.State], as)
                        .map(Step.leftMap(_)(s2 => Right((that, s2))))
                  )
                }
              }
            }

          case Right((that, s2)) =>
            that.step(s2.asInstanceOf[that.State], a).map(Step.leftMap(_)(s2 => Right((that, s2))))
        }

        def extract(state: State): IO[E1, C] =
          state match {
            case Left(s1) =>
              self.extract(s1).flatMap { b =>
                val that = f(b)

                that.initial.flatMap(s2 => that.extract(Step.state(s2)))
              }

            case Right((that, s2)) => that.extract(s2.asInstanceOf[that.State])
          }
      }

    final def seq[E1 >: E, C](that: Sink[E1, A, A, C]): Sink[E1, A, A, (B, C)] =
      flatMap(b => that.map(c => (b, c)))

    final def seqWith[E1 >: E, C, D](that: Sink[E1, A, A, C])(f: (B, C) => D): Sink[E1, A, A, D] =
      seq(that).map(f.tupled)

    final def ~[E1 >: E, C](that: Sink[E1, A, A, C]): Sink[E1, A, A, (B, C)] =
      seq(that)

    final def *>[E1 >: E, C](that: Sink[E1, A, A, C]): Sink[E1, A, A, C] =
      seq(that).map(_._2)

    final def <*[E1 >: E, C](that: Sink[E1, A, A, C]): Sink[E1, A, A, B] =
      seq(that).map(_._1)

    final def repeat0[S](z: S)(f: (S, B) => S): Sink[E, A, A, S] =
      new Sink[E, A, A, S] {
        type State = (Option[E], S, self.State)

        val initial = self.initial.map(step => Step.leftMap(step)((None, z, _)))

        def step(state: State, a: A): IO[E, Step[State, A]] =
          self
            .step(state._3, a)
            .redeem(
              e => IO.succeed(Step.done((Some(e), state._2, state._3), Chunk.empty)),
              step =>
                if (Step.cont(step)) IO.succeed(Step.more((state._1, state._2, Step.state(step))))
                else {
                  val s  = Step.state(step)
                  val as = Step.leftover(step)

                  self.extract(s).flatMap { b =>
                    self.initial.flatMap { init =>
                      self
                        .stepChunk(Step.state(init), as)
                        .redeemPure(
                          e => Step.done((Some(e), f(state._2, b), Step.state(init)), Chunk.empty),
                          s => Step.leftMap(s)((state._1, f(state._2, b), _))
                        )
                    }
                  }
                }
            )

        def extract(state: State): IO[E, S] =
          IO.succeed(state._2)
      }

    final def repeat: Sink[E, A, A, List[B]] =
      repeat0(List.empty[B])((bs, b) => b :: bs).map(_.reverse)

    final def repeatN(i: Int): Sink[E, A, A, List[B]] =
      repeat0[(Int, List[B])]((0, Nil))((s, b) => (s._1 + 1, b :: s._2)).untilOutput(_._1 >= i).map(_._2.reverse)

    final def repeatWhile0[S](p: A => Boolean)(z: S)(f: (S, B) => S): Sink[E, A, A, S] =
      new Sink[E, A, A, S] {
        type State = (S, self.State)

        val initial = self.initial.map(Step.leftMap(_)((z, _)))

        def step(state: State, a: A): IO[E, Step[State, A]] =
          if (!p(a)) self.extract(state._2).map(b => Step.done((f(state._1, b), state._2), Chunk(a)))
          else
            self.step(state._2, a).flatMap { step =>
              if (Step.cont(step)) IO.succeed(Step.more((state._1, Step.state(step))))
              else {
                val s  = Step.state(step)
                val as = Step.leftover(step)

                self.extract(s).flatMap { b =>
                  self.initial.flatMap { init =>
                    self.stepChunk(Step.state(init), as).map(Step.leftMap(_)((f(state._1, b), _)))
                  }
                }
              }
            }

        def extract(state: State): IO[E, S] =
          IO.succeed(state._1)
      }

    final def repeatWhile(p: A => Boolean): Sink[E, A, A, List[B]] =
      repeatWhile0(p)(List.empty[B])((bs, b) => b :: bs)
        .map(_.reverse)
  }
}
