package com.fabahaba.jedipus.primitive;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;

import com.fabahaba.jedipus.FutureLongReply;
import com.fabahaba.jedipus.FutureReply;
import com.fabahaba.jedipus.RedisClient.ReplyMode;
import com.fabahaba.jedipus.RedisPipeline;
import com.fabahaba.jedipus.cmds.Cmd;
import com.fabahaba.jedipus.cmds.PrimCmd;
import com.fabahaba.jedipus.exceptions.RedisUnhandledException;

final class PrimPipeline implements RedisPipeline {

  private final PrimRedisClient client;
  private final Queue<StatefulFutureReply<?>> pipelineReplies;
  private PrimMulti multi;

  PrimPipeline(final PrimRedisClient client) {

    this.client = client;
    this.pipelineReplies = new ArrayDeque<>();
  }

  private PrimMulti getMulti() {

    if (multi == null) {
      multi = new PrimMulti();
    }

    return multi;
  }

  <T> FutureReply<T> queueFutureReply(final Function<Object, T> builder) {
    return client.conn.isInMulti() ? queueMultiPipelinedReply(builder)
        : queuePipelinedReply(builder);
  }

  <T> FutureReply<T> queuePipelinedReply(final Function<Object, T> builder) {
    switch (client.conn.getReplyMode()) {
      case ON:
        final StatefulFutureReply<T> futureReply = new DeserializedFutureReply<>(builder);
        pipelineReplies.add(futureReply);
        return futureReply;
      case OFF:
        return null;
      case SKIP:
        client.conn.setReplyMode(ReplyMode.ON);
        return null;
      default:
        return null;
    }
  }

  <T> FutureReply<T> queueMultiPipelinedReply(final Function<Object, T> builder) {

    pipelineReplies.add(new DirectFutureReply<>());
    return getMulti().queueMultiPipelinedReply(builder);
  }


  FutureLongReply queueFutureReply(final LongUnaryOperator adapter) {
    return client.conn.isInMulti() ? queueMultiPipelinedReply(adapter)
        : queuePipelinedReply(adapter);
  }

  FutureLongReply queuePipelinedReply(final LongUnaryOperator adapter) {
    switch (client.conn.getReplyMode()) {
      case ON:
        final StatefulFutureReply<Void> futureReply = new AdaptedFutureLongReply(adapter);
        pipelineReplies.add(futureReply);
        return futureReply;
      case SKIP:
        client.conn.setReplyMode(ReplyMode.ON);
        return null;
      case OFF:
      default:
        return null;
    }
  }

  private FutureLongReply queueMultiPipelinedReply(final LongUnaryOperator adapter) {

    pipelineReplies.add(new DirectFutureReply<>());
    return multi.queueMultiPipelinedReply(adapter);
  }

  @Override
  public void close() {
    pipelineReplies.clear();

    if (multi != null) {
      multi.close();
    }

    client.conn.resetState();
  }

  @Override
  public RedisPipeline skip() {
    client.skip();
    return this;
  }

  @Override
  public RedisPipeline replyOff() {
    client.replyOff();
    return this;
  }

  @Override
  public ReplyMode getReplyMode() {
    return client.getReplyMode();
  }

  @Override
  public FutureReply<String> discard() {

    if (!client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "DISCARD without MULTI.");
    }

    client.conn.discard();
    return queuePipelinedReply(Cmd.STRING_REPLY);
  }

  @Override
  public void sync() {

    if (client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "EXEC your MULTI before calling SYNC.");
    }

    client.conn.flush();

    for (;;) {
      final StatefulFutureReply<?> response = pipelineReplies.poll();

      if (response == null) {
        return;
      }

      try {
        response.setReply(client.conn);
      } catch (final RedisUnhandledException re) {
        response.setException(re);
      }
    }
  }

  @Override
  public void primArraySync() {

    if (client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "EXEC your MULTI before calling SYNC.");
    }

    client.conn.flush();

    for (;;) {
      final StatefulFutureReply<?> response = pipelineReplies.poll();

      if (response == null) {
        return;
      }

      try {
        response.setMultiReply(client.conn.getLongArrayNoFlush());
      } catch (final RedisUnhandledException re) {
        response.setException(re);
      }
    }
  }

  @Override
  public FutureReply<String> multi() {

    if (client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "MULTI calls cannot be nested.");
    }

    client.conn.multi();
    return queuePipelinedReply(Cmd.STRING_REPLY);
  }

  @Override
  public FutureReply<Object[]> exec() {

    if (!client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "EXEC without MULTI.");
    }

    client.conn.exec();

    final StatefulFutureReply<Object[]> futureMultiExecReply =
        getMulti().createMultiExecFutureReply();

    pipelineReplies.add(futureMultiExecReply);

    return futureMultiExecReply;
  }

  @Override
  public FutureReply<long[]> primExec() {

    if (!client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "EXEC without MULTI.");
    }

    client.conn.exec();

    final StatefulFutureReply<long[]> futureMultiExecReply =
        getMulti().createPrimMultiExecFutureReply();

    pipelineReplies.add(futureMultiExecReply);

    return futureMultiExecReply;
  }

  @Override
  public FutureReply<long[][]> primArrayExec() {

    if (!client.conn.isInMulti()) {
      client.conn.drain();
      throw new RedisUnhandledException(null, "EXEC without MULTI.");
    }

    client.conn.exec();

    final StatefulFutureReply<long[][]> futureMultiExecReply =
        getMulti().createPrimArrayMultiExecFutureReply();

    pipelineReplies.add(futureMultiExecReply);

    return futureMultiExecReply;
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd) {
    client.conn.sendCmd(cmd.getCmdBytes());
    return queueFutureReply(cmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes());
    return queueFutureReply(subCmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final byte[] arg) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), arg);
    return queueFutureReply(subCmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final byte[]... args) {
    client.conn.sendCmd(cmd.getCmdBytes(), args);
    return queueFutureReply(cmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final byte[] arg) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), arg);
    return queueFutureReply(cmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final byte[]... args) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), args);
    return queueFutureReply(subCmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<T> cmd, final String... args) {
    client.conn.sendCmd(cmd.getCmdBytes(), args);
    return queueFutureReply(cmd);
  }

  @Override
  public <T> FutureReply<T> sendCmd(final Cmd<?> cmd, final Cmd<T> subCmd, final String... args) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), args);
    return queueFutureReply(subCmd);
  }

  @Override
  public FutureLongReply sendCmd(final PrimCmd cmd) {
    client.conn.sendCmd(cmd.getCmdBytes());
    return queueFutureReply(cmd);
  }

  @Override
  public FutureLongReply sendCmd(final Cmd<?> cmd, final PrimCmd subCmd) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes());
    return queueFutureReply(subCmd);
  }

  @Override
  public FutureLongReply sendCmd(final Cmd<?> cmd, final PrimCmd subCmd, final byte[] arg) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), arg);
    return queueFutureReply(subCmd);
  }

  @Override
  public FutureLongReply sendCmd(final Cmd<?> cmd, final PrimCmd subCmd, final byte[]... args) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), args);
    return queueFutureReply(subCmd);
  }

  @Override
  public FutureLongReply sendCmd(final PrimCmd cmd, final byte[] arg) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), arg);
    return queueFutureReply(cmd);
  }

  @Override
  public FutureLongReply sendCmd(final PrimCmd cmd, final byte[]... args) {
    client.conn.sendCmd(cmd.getCmdBytes(), args);
    return queueFutureReply(cmd);
  }

  @Override
  public FutureLongReply sendCmd(final Cmd<?> cmd, final PrimCmd subCmd, final String... args) {
    client.conn.sendSubCmd(cmd.getCmdBytes(), subCmd.getCmdBytes(), args);
    return queueFutureReply(subCmd);
  }

  @Override
  public FutureLongReply sendCmd(final PrimCmd cmd, final String... args) {
    client.conn.sendCmd(cmd.getCmdBytes(), args);
    return queueFutureReply(cmd);
  }

  @Override
  public String toString() {
    return new StringBuilder("PrimPipeline [client=").append(client).append("]").toString();
  }
}
