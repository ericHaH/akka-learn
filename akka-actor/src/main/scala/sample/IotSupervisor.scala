package sample


import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, Signal}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
object DeviceManager{
  sealed trait Command
  final case class DeviceRegistered(actorRef:ActorRef[Device.Command])
  final case class ReplyDeviceList()
  final case class RequestDeviceList(requestId:String,groupId:String,replyTo)
  final case class RequestTrackDevice(groupId:String,deviceId:String,replyTo:ActorRef[DeviceRegistered]) extends Command with DeviceGroup.Command
}
object DeviceGroup{
sealed trait Command
private final case class DeviceTerminated(device:ActorRef[Device.Command],groupId:String,deviceId:String)extends Command
}
//
class DeviceGroup(context:ActorContext[DeviceGroup.Command],groupId:String) extends AbstractBehavior[DeviceGroup.Command]{
  import DeviceGroup._
  import DeviceManager.{DeviceRegistered,ReplyDeviceList,RequestDeviceList,RequestTrackDevice}
  private var deviceIdToActor = Map.empty[String,ActorRef[Device.Command]]
  override def onMessage(msg:Command):Behavior[Command]= msg match {
    case trackMsg @ RequestTrackDevice(`groupId`,deviceId,replyTo)=>
      deviceIdToActor.get(deviceId) match {
        case Some(deviceActor)=>
          replyTo ! DeviceRegistered(deviceActor)
        case None =>
          context.log.info("Creating device actor of {}",trackMsg,deviceId)
          val deviceActor = context.spawn(Device(groupId,deviceId),s"device-$deviceId")
          deviceIdToActor += deviceId -> deviceActor
          replyTo ! DeviceRegistered(deviceActor)
      }
      this
    case RequestTrackDevice(gId,_,_)=>
      context.log.warn("Ignoring TrackDevice request for {}.This actor is responsible for {}",gId,groupId)
      this
    case DeviceTerminated(_,_,deviceId)=>
      context.log.info("Device actor for {} has been terminated",deviceId)
      this
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceGroup {} stopped",groupId)
      this
  }
}
object Device{
  def apply(groupId:String,deviceId:String)= Behaviors.setup[Command](context=>new Device(context,groupId,deviceId))
  sealed trait Command
  final case class RecordTemperature(requestId:Long,value:Double,replyTo:ActorRef[TemperatureRecoded]) extends Command
  final case class TemperatureRecoded(requestId:Long)
  final case class ReadTemperature(requestId:Long,replyTo:ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId:Long,value:Option[Double])
}
class Device(context:ActorContext[Device.Command],groupId:String,deviceId:String)extends AbstractBehavior[Device.Command]{
  import Device._
  var lastTemperatureReading:Option[Double]=None
  context.log.info("Device actor {}-{} started",groupId,deviceId)

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    case RecordTemperature(id,value,replyTo)=>
      context.log.info("Recording temperature reading {} with {}",value,id)
      lastTemperatureReading = Some(value)
      replyTo ! TemperatureRecoded(id)
      this
    case ReadTemperature(id,replyTo)=>
      replyTo ! RespondTemperature(id,lastTemperatureReading)
      this
  }
  override def  onSignal:PartialFunction[Signal,Behavior[Command]]={
    case PostStop =>
      context.log.info("Device actor {}-{} stopped",groupId,deviceId)
      this
  }
}
object IotSupervisor {
  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing](context=>new IotSupervisor(context))
}
class IotSupervisor(context:ActorContext[Nothing]) extends AbstractBehavior[Nothing]{
  context.log.info("IoT application started")
  override def onMessage(msg:Nothing):Behavior[Nothing]= Behaviors.unhandled
  override def onSignal:PartialFunction[Signal,Behavior[Nothing]]={
    case PostStop =>
      context.log.info("IoT Application stopped")
      this
  }
}

object IotApp{
  def main(args: Array[String]): Unit = {
    ActorSystem[Nothing](IotSupervisor(),"iot-system")
  }
}