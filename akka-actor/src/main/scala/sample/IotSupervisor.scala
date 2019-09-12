package sample


import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop, Signal}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}

import scala.concurrent.duration.{Duration, FiniteDuration}
object DeviceManager{
  sealed trait Command
  // 机器注册
  final case class DeviceRegistered(actorRef:ActorRef[Device.Command])

  // 请求组列表
  final case class RequestDeviceList(requestId:Long,groupId:String,replyTo:ActorRef[ReplyDeviceList])extends Command with DeviceGroup.Command
  final case class ReplyDeviceList(requestId:Long,devices:Set[String])
  // 获取管理的实例
  final case class RequestTrackDevice(groupId:String,deviceId:String,replyTo:ActorRef[DeviceRegistered]) extends Command with DeviceGroup.Command
  // 停止组
  private final case class DeviceGroupTerminated(groupId:String) extends Command
  sealed trait TemperatureReading
  final case class RequestAllTemperatures(requestId:Long,groupId:String,replyTo:ActorRef[ResponseAllTemperatures])
  final case class ResponseAllTemperatures(requestId:Long,temperatures:Map[String,TemperatureReading])
   final case class Temperature(value:Double)extends TemperatureReading
   case object TemperatureNotAvailable extends TemperatureReading
   case object DeviceNotAvailable extends TemperatureReading
  case object DeviceTimeOut extends TemperatureReading
}
class DeviceManager(context:ActorContext[DeviceManager.Command]) extends AbstractBehavior[DeviceManager.Command]{
  import DeviceManager._
  var groupIdToActor = Map.empty[String,ActorRef[DeviceGroup.Command]]
  context.log.info("DeviceManager started")
  override def onMessage(msg:Command):Behavior[Command]=msg match {
    case trackMsg@RequestTrackDevice(groupId, _, replyTo)=>
      groupIdToActor.get(groupId) match {
        case Some(ref)=>
          ref ! trackMsg
        case None =>
          context.log.info("Creating device group actor for {}",groupId)
          val groupActor = context.spawn(DeviceGroup(groupId),"group-"+groupId)
          groupActor ! trackMsg
          groupId += groupId ->groupActor
      }
      this
    case req @ RequestDeviceList(requestId,groupId,replyTo)=>
      groupIdToActor.get(groupId) match {
        case Some(ref)=>
          ref ! req
        case None =>
          replyTo ! ReplyDeviceList(requestId,Set.empty)
      }
      this
    case DeviceGroupTerminated(groupId)=>
      context.log.info("Device group actor for {} has been terminated ",groupId)
      groupIdToActor -= groupId
      this
  }
  override def onSignal:PartialFunction[Signal,Behavior[Command]]={
    case PostStop=>
      context.log.info("DeviceManager stopped")
      this
  }
}
object DeviceGroup{
  def apply(groupId: String):Behavior[Command] = Behaviors.setup[Command](context=>new DeviceGroup(context,groupId))
sealed trait Command
private final case class DeviceTerminated(device:ActorRef[Device.Command],groupId:String,deviceId:String)extends Command
}
//
class DeviceGroup(context:ActorContext[DeviceGroup.Command],groupId:String) extends AbstractBehavior[DeviceGroup.Command]{
  import DeviceGroup._
  import DeviceManager.{DeviceRegistered,ReplyDeviceList,RequestDeviceList,RequestTrackDevice}
  private var deviceIdToActor = Map.empty[String,ActorRef[Device.Command]]
  override def onMessage(msg:Command):Behavior[Command]= msg match {
    // 返回跟踪的机器
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
    // 处理非法请求
    case RequestTrackDevice(gId,_,_)=>
      context.log.warn("Ignoring TrackDevice request for {}.This actor is responsible for {}",gId,groupId)
      this
    // 请求所有的机器列表
    case RequestDeviceList(requestId,gId,replyTo)=>
      if(gId == groupId){
        replyTo ! ReplyDeviceList(requestId,deviceIdToActor.keySet)
        this
      }else Behaviors.unhandled
    // 去除注册的机器
    case DeviceTerminated(_,_,deviceId)=>
      context.log.info("Device actor for {} has been terminated",deviceId)
      deviceIdToActor -= deviceId
      this
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info("DeviceGroup {} stopped",groupId)
      this
  }
}
object Device{
  def apply(groupId:String,deviceId:String):Behavior[Command]= Behaviors.setup[Command](context=>new Device(context,groupId,deviceId))
  sealed trait Command

  final case class RecordTemperature(requestId:Long,value:Double,replyTo:ActorRef[TemperatureRecoded]) extends Command
  final case class TemperatureRecoded(requestId:Long)

  final case class ReadTemperature(requestId:Long,replyTo:ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId:Long,deviceId:String,value:Option[Double])

  case object Passivate extends Command
}
class Device(context:ActorContext[Device.Command],groupId:String,deviceId:String)extends AbstractBehavior[Device.Command]{
  import Device._
  var lastTemperatureReading:Option[Double]=None
  context.log.info("Device actor {}-{} started",groupId,deviceId)

  override def onMessage(msg: Command): Behavior[Command] = msg match {
    // 记录温度
    case RecordTemperature(id,value,replyTo)=>
      context.log.info("Recording temperature reading {} with {}",value,id)
      lastTemperatureReading = Some(value)
      replyTo ! TemperatureRecoded(id)
      this
    // 读取温度
    case ReadTemperature(id,replyTo)=>
      replyTo ! RespondTemperature(id,deviceId,lastTemperatureReading)
      this
    case Passivate=>Behaviors.stopped
  }
  override def  onSignal:PartialFunction[Signal,Behavior[Command]]={
    case PostStop =>
      context.log.info("Device actor {}-{} stopped",groupId,deviceId)
      this
  }
}

// 对一个模型的查询操作
object DeviceGroupQuery{

  trait Command

  final case class WrappedRespondTemperature(response:Device.RespondTemperature)extends Command
  private final case class DeviceTerminated(deviceId:String) extends Command
  private case object CollectionTimeout extends Command
}


class DeviceGroupQuery(deviceIdToActor:Map[String,ActorRef[Device.Command]],
                       requestId:Long,
                       requester:ActorRef[DeviceManager.ResponseAllTemperatures],
                       timeout:FiniteDuration,
                       context:ActorContext[DeviceGroupQuery.Command],
                       times:TimerScheduler[DeviceGroupQuery.Command]
                      )extends AbstractBehavior[DeviceGroupQuery.Command] {

  import DeviceGroupQuery._
  import DeviceManager.DeviceNotAvailable
  import DeviceManager.DeviceTimeOut
  import DeviceManager.ResponseAllTemperatures
  import DeviceManager.Temperature
  import DeviceManager.TemperatureNotAvailable
  import DeviceManager.TemperatureReading

  private var repliesSoFar = Map.empty[String,TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet

  // 定时执行一次
  times.startSingleTimer(CollectionTimeout,CollectionTimeout,timeout)
  // 消息适配器 返回一个接受返回消息的ref 这个ref就是本生的ref 只是把返回的消息包装成本类的强类型
  private val respondTemperatureAdapter = context.messageAdapter[Device.RespondTemperature](WrappedRespondTemperature.apply)

  //监控并发起请求 这个是这个请求的主方法
  deviceIdToActor.foreach{
    case (deviceId,device)=>
      context.watchWith(device,DeviceTerminated(deviceId))
      device ! Device.ReadTemperature(0,respondTemperatureAdapter)
  }

  // 请求控制
  override def onMessage(msg:Command):Behavior[Command]= msg match {
    case WrappedRespondTemperature(response)=>onRespondTemperature(response)
    case DeviceTerminated(deviceId) =>onDeviceTerminated(deviceId)
    case CollectionTimeout =>onCollectionTimeout()
  }
  // 获取哪些信息
  private def onRespondTemperature(response: Device.RespondTemperature):Behavior[Command]={
    val reading = response.value match {
      case Some(value) => Temperature(value)
      case None => TemperatureNotAvailable
    }
    val deviceId = response.deviceId
    repliesSoFar += (deviceId->reading)
    stillWaiting -= deviceId
    respondWhenAllCollected()
  }

  // 请求完成控制
  private def respondWhenAllCollected():Behavior[Command]={
    if(stillWaiting.isEmpty){
      requester! ResponseAllTemperatures(requestId, repliesSoFar)
      Behaviors.stopped
    }else{
      this
    }
  }

  // 当监控到有设备终止将其移除
  private def onDeviceTerminated(deviceId:String):Behavior[Command]={
    if(stillWaiting(deviceId)){
      repliesSoFar += (deviceId->DeviceNotAvailable)
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  }

  // 如果有超时则返回超时
  private def onCollectionTimeout():Behavior[Command]={
    repliesSoFar ++= stillWaiting.map(deviceId =>deviceId->DeviceTimeOut)
    stillWaiting=Set.empty
    respondWhenAllCollected()
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