package chee.cli

import chee.cli.CryptOptions.{Opts => CryptOpts}
import chee.cli.LsOptions.{Opts => LsOpts}
import chee.query.Progress
import chee.properties._
import chee.properties.MapGet._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

trait CryptCommand { self: ScoptCommand with AbstractLs =>

  case class Opts(
    lsOpts: LsOpts = LsOptions.Opts(),
    cryptOpts: CryptOpts = CryptOptions.Opts(),
    parallel: Boolean = false) {
    def updateCryptOpts(f: CryptOpts => CryptOpts) =
      copy(cryptOpts = f(cryptOpts))
    def updateLsOpts(f: LsOpts =>  LsOpts) =
      copy(lsOpts = f(lsOpts))
  }

  type T = Opts

  val defaults = Opts()

  lazy val parser = new Parser with LsOptions[Opts] with CryptOptions[Opts] with ProcessingOptions[Opts] {

    addLsOptions(_ updateLsOpts _)

    note(s"\n${name.capitalize} options:")
    concurrent() action { (_, c) => c.copy(parallel = true) }
    if (name == Decrypt.name) {
      addDecryptOptions(_ updateCryptOpts _, title = None)
    } else {
      addEncryptOptions(_ updateCryptOpts _, title = None)
    }

    addQuery(_ updateLsOpts _)
  }

  def progress(parallel: Boolean) = Progress.seq[Unit, Int](
    Progress.before(valueForce(Ident.filename).map { f =>
      if (! parallel) out(s"${name.capitalize}ing $f … ")
    }),
    Progress.after { n =>
      if (!parallel) outln("done")
      else out(".")
      n + 1
    },
    Progress.done { (n, dur) =>
      val msg = s"${name.capitalize}ed $n files in ${chee.Timing.format(dur)}"
      logger.trace(msg)
      if (parallel) out("\n")
      outln(msg)
    }
  )

  def runProcess(props: Stream[LazyMap], processor: MapGet[Boolean], parallel: Boolean): Unit = {
    val action = unit(())
    val prog = progress(parallel)
    if (parallel) {
      prog.foreach(0)(MapGet.parfilter(props, processor), action)
    } else {
      prog.foreach(0)(MapGet.filter(props, processor), action)
    }
  }

  def processingAction(cfg: Config, opts: CryptOptions.Opts): MapGet[Boolean] 

  def exec(cfg: Config, opts: Opts): Unit = {
    val proc = processingAction(cfg, opts.cryptOpts)
    val props = find(cfg, opts.lsOpts)
    runProcess(props, proc, opts.parallel)
  }
}
