import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Props, _}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka_typed.TypedCalculatorWriteSide.{Add, Added, Command, Divide, Divided, Event, Multiplied, Multiply}
import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Props, _}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka_typed.CalculatorRepository.{getLatestOffsetAndResult, initDataBase, updateResultAndOfsset}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.{Failure, Success}

case class Action(value: Int, name: String)

object akka_typed {
  trait CborSerializable

  val persId = PersistenceId.ofUniqueId("001")

  object TypedCalculatorWriteSide {
    sealed trait Command
    case class Add(amount: Int)      extends Command
    case class Multiply(amount: Int) extends Command
    case class Divide(amount: Int)   extends Command

    sealed trait Event
    case class Added(id: Int, amount: Int)      extends Event
    case class Multiplied(id: Int, amount: Int) extends Event
    case class Divided(id: Int, amount: Int)    extends Event

    final case class State(value: Int) extends CborSerializable {
      def add(amount: Int): State      = copy(value = value + amount)
      def multiply(amount: Int): State = copy(value = value * amount)
      def divide(amount: Int): State   = copy(value = value / amount)
    }

    object State {
      val empty = State(0)
    }

    def apply(): Behavior[Command] =
      Behaviors.setup { ctx =>
        EventSourcedBehavior[Command, Event, State](
          persistenceId = persId,
          State.empty,
          (state, command) => handleCommand("001", state, command, ctx),
          (state, event) => handleEvent(state, event, ctx)
        )
      }

    def handleCommand(
                       persistenceId: String,
                       state: State,
                       command: Command,
                       ctx: ActorContext[Command]
                     ): Effect[Event, State] =
      command match {
        case Add(amount) =>
          ctx.log.info(s"Receive adding for number: $amount and state is ${state.value}")
          val added = Added(persistenceId.toInt, amount)
          Effect
            .persist(added)
            .thenRun { x =>
              ctx.log.info(s"The state result is ${x.value}")
            }
        case Multiply(amount) =>
          ctx.log.info(s"Receive multiplying for number: $amount and state is ${state.value}")
          Effect
            .persist(Multiplied(persistenceId.toInt, amount))
            .thenRun { newState =>
              ctx.log.info(s"The state result is ${newState.value}")
            }
        case Divide(amount) =>
          ctx.log.info(s"Receive dividing for number: $amount and state is ${state.value}")
          Effect
            .persist(Divided(persistenceId.toInt, amount))
            .thenRun { x =>
              ctx.log.info(s"The state result is ${x.value}")
            }
      }

    def handleEvent(state: State, event: Event, ctx: ActorContext[Command]): State =
      event match {
        case Added(_, amount) =>
          ctx.log.info(s"Handing event amount is $amount and state is ${state.value}")
          state.add(amount)
        case Multiplied(_, amount) =>
          ctx.log.info(s"Handing event amount is $amount and state is ${state.value}")
          state.multiply(amount)
        case Divided(_, amount) =>
          ctx.log.info(s"Handing event amount is $amount and state is ${state.value}")
          state.divide(amount)
      }
  }

  case class TypedCalculatorReadSide(system: ActorSystem[NotUsed]) {
    initDataBase

    implicit val materializer            = system.classicSystem
    implicit val ec                      = system.executionContext
    var (offset, latestCalculatedResult) = getLatestOffsetAndResult
    val startOffset: Int                 = if (offset == 1) 1 else offset + 1

    val readJournal: CassandraReadJournal =
      PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    implicit val session = SlickSession.forConfig("postgres")
    import session.profile.api._
    val source: Source[EventEnvelope, NotUsed] = readJournal
      .eventsByPersistenceId("001", startOffset, Long.MaxValue)

    case class Info(res:Double, offset: Long)

    source
      .map { x =>
        println(x.toString())
        x
      }
      .map(e => (e.sequenceNr, e.event))
      .collectType[(Long, Event)]
      .map({
        case (sequenceNr, Added(_, amount)) =>
          latestCalculatedResult += amount
          Info(latestCalculatedResult, sequenceNr)
        case (sequenceNr, Multiplied(_, amount)) =>
          latestCalculatedResult *= amount
          Info(latestCalculatedResult, sequenceNr)
        case (sequenceNr, Divided(_, amount)) =>
          latestCalculatedResult /= amount
          Info(latestCalculatedResult, sequenceNr)
      })
      .async
      .via(
        Slick
          .flow(info =>
            sqlu"update public.result set calculated_value = ${info.res}, write_side_offset = ${info.offset} where id = 1"
          )
      )
      .runWith(Sink.ignore)
  }

  object CalculatorRepository {
    import scalikejdbc._

    def initDataBase: Unit = {
      Class.forName("org.postgresql.Driver")
      val poolSettings = ConnectionPoolSettings(initialSize = 10, maxSize = 100)

      val cfg = ConfigFactory.load().getConfig("postgres.db.properties")

      ConnectionPool.singleton(
        url = cfg.getString("url"),
        user = cfg.getString("user"),
        password = cfg.getString("password"),
        poolSettings
      )
    }

    def getLatestOffsetAndResult: (Int, Double) = {
      val entities =
        DB readOnly { session =>
          session.list("select * from public.result where id = 1;") { row =>
            (row.int("write_side_offset"), row.double("calculated_value"))
          }
        }
      entities.head
    }

    def updateResultAndOfsset(calculated: Double, offset: Long): Unit = {
      using(DB(ConnectionPool.borrow())) { db =>
        db.autoClose(true)
        db.localTx {
          _.update(
            "update public.result set calculated_value = ?, write_side_offset = ? where id = ?",
            calculated,
            offset,
            1
          )
        }
      }
    }
  }

  def apply(): Behavior[NotUsed] =
    Behaviors.setup { ctx =>
      val writeActorRef = ctx.spawn(TypedCalculatorWriteSide(), "Calculato", Props.empty)

      writeActorRef ! Add(10)
      writeActorRef ! Multiply(2)
      writeActorRef ! Divide(5)

      // 0 + 10 = 10
      // 10 * 2 = 20
      // 20 / 5 = 4

      Behaviors.same
    }

  def execute(comm: Command): Behavior[NotUsed] =
    Behaviors.setup { ctx =>
      val writeActorRef = ctx.spawn(TypedCalculatorWriteSide(), "Calculato", Props.empty)

      writeActorRef ! comm

      Behaviors.same
    }

  def main(args: Array[String]): Unit = {
    val value                                 = akka_typed()
    implicit val system: ActorSystem[NotUsed] = ActorSystem(value, "akka_typed")

    TypedCalculatorReadSide(system)

    implicit val executionContext = system.executionContext
  }
}