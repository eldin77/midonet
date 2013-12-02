/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._

import java.util.UUID

import akka.actor.ActorSystem

import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest._

import org.midonet.cluster.services.MidostoreSetupService
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.MockConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.reactor.MockReactorModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice.{MidolmanActorsModule, MockCacheModule, MockMonitoringStoreModule, MidolmanModule}
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services._
import org.midonet.midolman.version.guice.VersionModule
import akka.testkit.TestActorRef

trait MockMidolmanActors extends BeforeAndAfter {
    this: Suite =>

    var injector: Injector = null

    private[this] val actorsService = new MockMidolmanActorsService
    implicit def actorSystem: ActorSystem = actorsService.system

    // These methods can be overridden by each class mixing MockMidolmanActors
    // to add custom operations before each test and after each tests
    protected def registerActors: List[(Referenceable, () => MessageAccumulator)] = List()
    protected def beforeTest() { }
    protected def afterTest() { }

    implicit def toActorRef(ref: Referenceable): TestActorRef[MessageAccumulator] =
        actorsService.actor(ref)
    implicit def toMessageAccumulator(ref: Referenceable): MessageAccumulator =
        toActorRef(ref).underlyingActor
    implicit def toTypedActor(ref: Referenceable) = new {
        def as[A <: MessageAccumulator] =
            toMessageAccumulator(ref).asInstanceOf[A]
    }

    before {
        try {
            val config = fillConfig(new HierarchicalConfiguration)
            injector = Guice.createInjector(getModules(config))

            actorsService.register(registerActors)

            injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
            injector.getInstance(classOf[MidolmanService]).startAndWait()

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        afterTest()
        actorSystem.shutdown()
    }

    protected def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    protected def getModules(config: HierarchicalConfiguration) = {
        List(
            new VersionModule(),
            new SerializationModule(),
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockCacheModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                            .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new MockReactorModule(),
            new MockMonitoringStoreModule(),
            new ClusterClientModule(),
            new MidolmanActorsModule {
                override def configure() {
                    bind(classOf[MidolmanActorsService])
                        .toInstance(actorsService)
                    expose(classOf[MidolmanActorsService])
                }
            },
            new MidolmanModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                        .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            }
        )
    }
}