package chee.properties

import better.files._
import java.time.Duration

case class MapGet[+A](run: LazyMap => (LazyMap, A)) {

  def map[B](f: A => B): MapGet[B] = MapGet { map =>
    val (next, a) = run(map)
    (next, f(a))
  }

  def flatMap[B](f: A => MapGet[B]): MapGet[B] = MapGet { map =>
    val (next, va) = run(map)
    f(va).run(next)
  }

  def combine[B, C](h: MapGet[B])(f: (A, B) => C): MapGet[C] = MapGet { map =>
    val (nexta, a) = run(map)
    val (nextb, b) = h.run(nexta)
    (nextb, f(a, b))
  }

  def result(lmap: LazyMap): A = run(lmap)._2

  def modify(f: LazyMap => LazyMap): MapGet[A] =
    flatMap(a => MapGet.modify(f).map(_ => a))

  def timed: MapGet[(A, Duration)] = MapGet { m =>
    val ((nextm, a), dur) = chee.Timing.timedResult(run(m))
    (nextm, (a, dur))
  }

  def around[B](before: MapGet[Unit], after: (A, Duration) => MapGet[B]): MapGet[B]
    = MapGet.aroundEffect(before, after)(this)
}

object MapGet {
  type GetProp = MapGet[Option[Property]]
  type GetStr = MapGet[Option[String]]

  val virtualKeys: MapGet[Set[Ident]] = get.map(_.virtualKeys)
  val propertyKeys: MapGet[Set[Ident]] = get.map(_.propertyKeys)
  val allKeys: MapGet[Set[Ident]] = propertyKeys.combine(virtualKeys){ _ ++ _ }

  def idents(includeVirtual: Boolean): MapGet[Seq[Ident]] = {
    val sort: Set[Ident] => Seq[Ident] =
      Ident.sort(_, includeDefaults = true, includeVirtual)

    if (includeVirtual) allKeys.map(sort)
    else propertyKeys.map(sort)
  }

  def find(id: Ident): GetProp = MapGet(map => map(id))

  def value(id: Ident): GetStr = find(id).map(_.map(_.value))

  def valueForce(id: Ident): MapGet[String] =
    value(id).map {
      case Some(s) => s
      case None => sys.error(s"Property not available: ${id.name}")
    }

  def path: MapGet[File] =
    valueForce(Ident.path).map(File(_))

  def existingPath: MapGet[Option[File]] =
    path.map(p => Option(p).filter(_.exists))

  def convert[T](id: Ident, conv: Converter[T]) =
    value(id).map(v => v.map(conv.parse(_)))

  def intValue(id: Ident) = convert(id, IntConverter)

  def unit[A](a: A): MapGet[A] = MapGet(map => (map, a))

  def pair[A, B](a: MapGet[A], b: MapGet[B]): MapGet[(A, B)] =
    a.combine(b)((av, bv) => (av, bv))

  def seq[A](gs: Seq[MapGet[A]]): MapGet[List[A]] =
    gs.foldRight(unit(List[A]())){ (e, acc) =>
      e.combine(acc)(_ :: _)
    }

  def concat(ds: Seq[MapGet[String]]): MapGet[String] =
    ds.foldLeft(unit("")) { (acc, e) =>
      acc.combine(e){ (last, s) => last + s }
    }

  def joinEitherBiased[A, B](a: Seq[MapGet[Either[A, B]]]): MapGet[Either[A, List[B]]] = {
    val zero: Either[A, List[B]] = Right(Nil)
    a.toStream.foldRight(unit(zero)) { (e, acc) =>
      e.combine(acc) { (ev, accv) =>
        if (accv.isLeft) accv
        else if (ev.isLeft) Left(ev.left.get)
        else Right(ev.right.get :: accv.right.get)
      }
    }
  }

  def set(lm: LazyMap): MapGet[Unit] = MapGet(_ => (lm, ()))

  def get: MapGet[LazyMap] = MapGet(m => (m, m))

  def modify(f: LazyMap => LazyMap): MapGet[Unit] =
    for {
      m <- get
      _ <- set(f(m))
    } yield ()

  def filter(iter: Stream[LazyMap], pred: MapGet[Boolean]): Stream[LazyMap] =
    iter.map(pred.run).collect {
      case (m, true) => m
    }

  def aroundEffect[A, B](before: MapGet[Unit], after: (A, Duration) => MapGet[B])(geta: MapGet[A]): MapGet[B] =
    before.flatMap( _ =>
      geta.timed.flatMap { case (a, dur) =>
        after(a, dur)
      }
    )

  def fold[T](zero: T, maps: Iterable[LazyMap])(action: T => MapGet[T]): T =
    maps.foldLeft(zero) { (t, m) => action(t).result(m) }

  def foreach[T](maps: Iterable[LazyMap], action: Int => MapGet[T]): Int =
    fold(0, maps)(n => action(n).map(_ => n+1))

  def foreach[T](maps: Iterable[LazyMap], action: MapGet[T]): Int =
    foreach(maps, _ => action)

}
