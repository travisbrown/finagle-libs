package com.twitter.finagle.irc.server

import com.twitter.finagle.irc._
import com.twitter.finagle.irc.protocol._
import com.twitter.concurrent.Broker
import com.twitter.util.Future
import scala.collection.mutable

case class ChannelUser(user: SessionUser) {
  var op: Boolean = false
  var voice: Boolean = false

  def chanModeString = {
    var out = ""
    if (op) out += "@"
    if (voice) out += "+"
    out
  }

  def modeString =
    user.modeString + chanModeString

  def ! = user ! _

  def part = user.part(_)
  def join = user.join(_)

  def session = user.session
  def nick = user.nick
  def name = user.name
  def realName = user.realName
  def hostName = user.hostName

  def isVisible = !user.invisible

  def display =
    chanModeString + nick
}

case class Channel(name: String) {
  private[this] var _topic: Option[String] = None
  private[this] val _users = mutable.Set.empty[ChannelUser]
  private[this] val _modes = mutable.Set.empty[String]

  def topic = _topic

  def findUser(session: Session): Option[ChannelUser] =
    _users find { _.user == session.user }

  def users: Set[Session] =
    _users map { _.session } toSet

  def modes: Set[String] =
    _modes toSet

  def visibleUsers =
    _users filter { _.isVisible }

  def !(msg: Message): Future[Unit] =
    Future.collect(_users map { _ ! msg } toSeq) flatMap { _ => Future.Done }

  def setTopic(session: Session, topic: Option[String]): Future[Unit] = {
    // TODO: check permissions
    _topic = topic
    this ! session.serverMsg(Topic(name, _topic))
  }

  def who(session: Session): Future[Unit] = Future.collect(
    _users map { user =>
      // TODO: hopcount, servername
      session ! RplWhoReply(name, user.name, user.hostName, "", user.nick, user.modeString, 0, user.realName)
    } toSeq
  ) flatMap { _ => session ! RplEndOfWho(name) }

  def msg(session: Session, text: String): Future[Unit] = {
    //TODO: check for permission
    val user = session.user
    Future.collect(
      _users map { u =>
        if (u.user == session.user) Future.Done
        else u ! session.serverMsg(PrivMsg(Seq(name), text))
      } toSeq
    ) flatMap { _ => Future.Done }
  }

  // TODO
  def op(session: Session): Future[Unit] =
    join(session)

  def join(session: Session): Future[Unit] = {
    if (_users find { _.user == session.user } isDefined)
      return Future.Done

    // TODO: permissions check
    // TODO: op on new room?
    val user = new ChannelUser(session.user)
    _users += user
    user.join(this)

    for {
      _ <- this ! session.serverMsg(Join(Seq(name)))

      _ <- user ! RplChannelModeIs(name, modes)
      _ <- user ! (_topic match {
             case Some(t) => RplTopic(name, t)
             case None => RplNoTopic(name)
           })
      _ <- user ! RplNameReply(name, _users map { _.display } toSet)
      _ <- user ! RplEndOfNames(name)
    } yield ()
  }

  def part(session: Session): Future[Unit] = findUser(session) match {
    case Some(user) =>
      this ! session.serverMsg(Part(Seq(name), Some(user.nick))) onSuccess { _ =>
        _users -= user
        user.part(this)
      }

    case None =>
      Future.Done
  }

  def quit(session: Session, msg: Option[String]): Future[Unit] = findUser(session) match {
    case Some(user) =>
      _users -= user
      this ! session.serverMsg(Quit(msg))

    case None =>
      Future.Done
  }
}

