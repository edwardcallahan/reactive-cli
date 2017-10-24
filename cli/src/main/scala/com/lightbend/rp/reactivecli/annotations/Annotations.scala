/*
 * Copyright 2017 Lightbend, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.rp.reactivecli.annotations

import com.lightbend.rp.reactivecli.annotations.HttpEndpoint.HttpAcl

import scala.collection.immutable.Seq
import scala.util.Try
import scala.util.matching.Regex

case class Annotations(
  appName: Option[String],
  diskSpace: Option[Long],
  memory: Option[Long],
  nrOfCpus: Option[Double],
  endpoints: Map[String, Endpoint],
  volumes: Map[String, Volume],
  privileged: Boolean,
  healthCheck: Option[Check],
  readinessCheck: Option[Check],
  environmentVariables: Map[String, EnvironmentVariable],
  version: Option[Version])

/**
 * Parses annotations in the RP format (typically stored in Docker labels)
 *
 * Example Input:
 *
 * Map(
 *   "com.lightbend.rp.disk-space" -> "65536",
 *   "com.lightbend.rp.environment-variables.0.type -> "literal",
 *   "com.lightbend.rp.environment-variables.0.name -> "GREETING",
 *   "com.lightbend.rp.environment-variables.0.value -> "Hello!"
 * )
 *
 * Example Output:
 *
 * Annotations(
 *   diskSpace = Some(65535L),
 *   environmentVariables = Map(
 *     "GREETING" -> LiteralEnvironmentVariable("HELLO!")
 *   )
 * )
 */
object Annotations {
  def apply(labels: Map[String, String]): Annotations = {
    val appVersion = version(labels)
    Annotations(
      appName = appName(labels),
      diskSpace = diskSpace(labels),
      memory = memory(labels),
      nrOfCpus = nrOfCpus(labels),
      endpoints = endpoints(selectArray(labels, ns("endpoints")), appVersion),
      volumes = volumes(selectArray(labels, ns("volumes"))),
      privileged = privileged(labels),
      healthCheck = check(selectSubset(labels, ns("health-check"))),
      readinessCheck = check(selectSubset(labels, ns("readiness-check"))),
      environmentVariables = environmentVariables(selectArray(labels, ns("environment-variables"))),
      version = appVersion)
  }

  private[annotations] def appName(labels: Map[String, String]): Option[String] =
    labels
      .get(ns("app-name"))

  private[annotations] def diskSpace(labels: Map[String, String]): Option[Long] =
    labels
      .get(ns("disk-space"))
      .flatMap(decodeLong)

  private[annotations] def memory(labels: Map[String, String]): Option[Long] =
    labels
      .get(ns("memory"))
      .flatMap(decodeLong)

  private[annotations] def nrOfCpus(labels: Map[String, String]): Option[Double] =
    labels
      .get(ns("nr-of-cpus"))
      .flatMap(decodeDouble)

  private[annotations] def privileged(labels: Map[String, String]): Boolean =
    labels
      .get(ns("privileged"))
      .flatMap(decodeBoolean)
      .getOrElse(false)

  private[annotations] def version(labels: Map[String, String]): Option[Version] =
    for {
      majorStr <- labels.get(ns("version-major"))
      minorStr <- labels.get(ns("version-minor"))
      patchStr <- labels.get(ns("version-patch"))
      major <- decodeInt(majorStr)
      minor <- decodeInt(minorStr)
      patch <- decodeInt(patchStr)
    } yield Version(major, minor, patch, labels.get(ns("version-patch-label")))

  private[annotations] def check(check: Map[String, String]): Option[Check] = {
    for {
      typ <- check.get("type")
      value <- typ match {
        case "command" =>
          val args = selectArray(check, "args").flatMap(_.values)

          if (args.isEmpty)
            None
          else
            Some(CommandCheck(args))

        case "http" =>
          for {
            intervalSeconds <- check.get("interval").flatMap(decodeInt)
            path <- check.get("path")
            port = check.get("port").flatMap(decodeInt).getOrElse(0)
            serviceName = check.getOrElse("service-name", "")

            if port != 0 || serviceName != ""
          } yield {
            val checkPort = if (port != 0) Check.PortNumber(port) else Check.ServiceName(serviceName)
            HttpCheck(checkPort, intervalSeconds, path)
          }

        case "tcp" =>
          for {
            intervalSeconds <- check.get("interval").flatMap(decodeInt)
            port = check.get("port").flatMap(decodeInt).getOrElse(0)
            serviceName = check.getOrElse("service-name", "")

            if port != 0 || serviceName != ""
          } yield {
            val checkPort = if (port != 0) Check.PortNumber(port) else Check.ServiceName(serviceName)
            TcpCheck(checkPort, intervalSeconds)
          }
      }
    } yield value
  }

  private[annotations] def endpoints(endpoints: Seq[Map[String, String]], version: Option[Version]): Map[String, Endpoint] =
    endpoints.flatMap(endpoint(_, version)).toMap

  private[annotations] def endpoint(entry: Map[String, String], version: Option[Version]): Option[(String, Endpoint)] =
    entry.get("protocol")
      .collect {
        case "http" => endpointHttp(version, entry)
        case "tcp" => endpointTcp(version, entry)
        case "udp" => endpointUdp(version, entry)
      }
      .flatten
      .map(v => v.name -> v)

  private[annotations] def endpointHttp(version: Option[Version], entry: Map[String, String]): Option[HttpEndpoint] =
    entry.get("name").map(
      HttpEndpoint(
        _,
        entry.get("port").flatMap(decodeInt).getOrElse(0),
        entry.get("version").flatMap(decodeInt).orElse(version.map(_.major)),
        aclsHttp(selectArray(entry, "acls"))))

  private[annotations] def endpointTcp(version: Option[Version], entry: Map[String, String]): Option[TcpEndpoint] =
    entry.get("name").map(
      TcpEndpoint(
        _,
        entry.get("port").flatMap(decodeInt).getOrElse(0),
        entry.get("version").flatMap(decodeInt).orElse(version.map(_.major))))

  private[annotations] def endpointUdp(version: Option[Version], entry: Map[String, String]): Option[UdpEndpoint] =
    entry.get("name").map(
      UdpEndpoint(
        _,
        entry.get("port").flatMap(decodeInt).getOrElse(0),
        entry.get("version").flatMap(decodeInt).orElse(version.map(_.major))))

  private[annotations] def aclsHttp(acls: Seq[Map[String, String]]): Seq[HttpAcl] =
    acls
      .flatMap(entry =>
        for {
          typ <- entry.get("type")
          value <- typ match {
            case "http" => entry.get("expression").map(HttpAcl.apply)
            case _ => None
          }
        } yield value)

  private[annotations] def environmentVariables(variables: Seq[Map[String, String]]): Map[String, EnvironmentVariable] =
    variables
      .flatMap(entry =>
        for {
          typ <- entry.get("type")
          name <- entry.get("name")
          value <- typ match {
            case "literal" =>
              entry.get("value").map(LiteralEnvironmentVariable.apply)

            case "configMap" =>
              for {
                mapName <- entry.get("map-name")
                key <- entry.get("key")
              } yield kubernetes.ConfigMapEnvironmentVariable(mapName, key)

            case "fieldRef" =>
              entry.get("field-path").map(kubernetes.FieldRefEnvironmentVariable.apply)

            case _ =>
              None
          }
        } yield name -> value)
      .toMap

  private[annotations] def volumes(volumes: Seq[Map[String, String]]): Map[String, Volume] =
    volumes
      .flatMap(entry =>
        for {
          typ <- entry.get("type")
          guestPath <- entry.get("guest-path")
          value <- typ match {
            case "host-path" =>
              entry.get("path").map(HostPathVolume.apply)

            case "secret" =>
              entry.get("secret").map(SecretVolume.apply)

            case _ =>
              None
          }
        } yield guestPath -> value)
      .toMap

  private[annotations] def decodeBoolean(s: String) =
    if (s == "true")
      Some(true)
    else if (s == "false")
      Some(false)
    else
      None

  private[annotations] def decodeDouble(s: String) = {
    // @FIXME Once https://github.com/scala-native/scala-native/pull/1047 is merged we can remove the regex guard

    if (!s.matches("^-?[0-9]+([.][0-9]*)?$"))
      None
    else
      Try(s.toDouble)
        .toOption
  }

  private[annotations] def decodeInt(s: String) =
    Try(s.toInt).toOption

  private[annotations] def decodeLong(s: String) =
    Try(s.toLong).toOption

  private[annotations] def selectArray(keys: Map[String, String], namespace: String): Seq[Map[String, String]] = {
    val nsPattern = s"^${Regex.quote(namespace)}\\.([0-9]+)(\\.(.+))?$$".r

    keys
      .flatMap {
        case (key, value) =>
          val groups =
            nsPattern
              .findAllMatchIn(key)
              .toVector
              .headOption
              .map(_.subgroups)

          groups match {
            case Some(index :: _ :: keyOrNull :: Nil) =>
              Some((index.toInt, Option(keyOrNull).getOrElse(""), value))
            case _ =>
              None
          }
      }
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .map(_._2.map(entry => (entry._2, entry._3)).toMap)
  }

  private[annotations] def selectSubset(keys: Map[String, String], namespace: String): Map[String, String] = {
    keys.flatMap {
      case (key, value) =>
        val prefix = namespace + "."

        if (key.startsWith(prefix))
          Some(key.drop(prefix.length) -> value)
        else
          None
    }
  }

  private def ns(key: String*): String =
    (Seq("com", "lightbend", "rp") ++ key.map(_.toString.filter(_ != "."))).mkString(".")
}