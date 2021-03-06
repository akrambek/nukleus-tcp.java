/**
 * Copyright 2016-2020 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.tcp.internal.streams.rfc793;

import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.IntStream.generate;
import static org.junit.rules.RuleChain.outerRule;
import static org.reaktivity.reaktor.test.ReaktorRule.EXTERNAL_AFFINITY_MASK;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;
import org.reaktivity.nukleus.tcp.internal.SocketChannelHelper;
import org.reaktivity.nukleus.tcp.internal.SocketChannelHelper.OnDataHelper;
import org.reaktivity.reaktor.test.ReaktorRule;

@RunWith(org.jboss.byteman.contrib.bmunit.BMUnitRunner.class)
public class ClientIOExceptionFromWriteIT
{
    private final K3poRule k3po = new K3poRule()
            .addScriptRoot("route", "org/reaktivity/specification/nukleus/tcp/control/route")
            .addScriptRoot("server", "org/reaktivity/specification/tcp/rfc793")
            .addScriptRoot("client", "org/reaktivity/specification/nukleus/tcp/streams/rfc793");

    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));

    private final ReaktorRule reaktor = new ReaktorRule()
        .nukleus("tcp"::equals)
        .controller("tcp"::equals)
        .directory("target/nukleus-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(4096)
        .affinityMask("target#0", EXTERNAL_AFFINITY_MASK)
        .clean();

    @Rule
    public final TestRule chain = outerRule(SocketChannelHelper.RULE)
                    .around(reaktor).around(k3po).around(timeout);

    @Test
    @Specification({
        "${route}/client.host/controller",
        "${client}/client.sent.data.received.abort.and.reset/client"
    })
    @BMRule(name = "onApplicationData",
        targetClass = "^java.nio.channels.SocketChannel",
        targetMethod = "write(java.nio.ByteBuffer)",
        condition = "callerEquals(\"TcpClientFactory$TcpClient.onApplicationData\", true, 2)",
        action = "throw new IOException(\"Simulating an IOException from write\")"
    )
    public void shouldAbortAndResetWhenImmediateWriteThrowsIOException() throws Exception
    {
        try (ServerSocketChannel server = ServerSocketChannel.open())
        {
            server.setOption(SO_REUSEADDR, true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));

            k3po.start();
            k3po.awaitBarrier("ROUTED_CLIENT");

            try (SocketChannel channel = server.accept())
            {
                k3po.finish();
            }
        }
    }

    @Test
    @Specification({
        "${route}/client.host/controller",
        "${client}/client.sent.data.received.abort.and.reset/client"
    })
    @BMRules(rules = {
        @BMRule(name = "onData",
        helper = "org.reaktivity.nukleus.tcp.internal.SocketChannelHelper$OnDataHelper",
        targetClass = "^java.nio.channels.SocketChannel",
        targetMethod = "write(java.nio.ByteBuffer)",
        condition = "callerEquals(\"TcpClientFactory$TcpClient.onApplicationData\", true, 2)",
        action = "return doWrite($0, $1);"
        ),
        @BMRule(name = "handleWrite",
        targetClass = "^java.nio.channels.SocketChannel",
        targetMethod = "write(java.nio.ByteBuffer)",
        condition = "callerEquals(\"TcpClientFactory$TcpClient.onNetworkWritable\", true, 2)",
        action = "throw new IOException(\"Simulating an IOException from write\")"
        )
    })
    public void shouldAbortAndResetWhenDeferredWriteThrowsIOException() throws Exception
    {
        OnDataHelper.fragmentWrites(generate(() -> 0));
        try (ServerSocketChannel server = ServerSocketChannel.open())
        {
            server.setOption(SO_REUSEADDR, true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));

            k3po.start();
            k3po.awaitBarrier("ROUTED_CLIENT");

            try (SocketChannel channel = server.accept())
            {
                k3po.finish();
            }
        }
    }
}
