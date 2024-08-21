// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2024 Jiuyang Liu <liu@jiuyang.me>

package org.chipsalliance.gcd

import chisel3._
import chisel3.experimental.hierarchy.{instantiable, public, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.ltl.Property.{eventually, not}
import chisel3.ltl.{AssertProperty, CoverProperty, Delay, Sequence}
import chisel3.probe.{define, Probe, ProbeValue}
import chisel3.properties.{AnyClassType, Class, Property}
import chisel3.util.circt.dpi.RawUnclockedNonVoidFunctionCall
import chisel3.util.{DecoupledIO, HasExtModuleInline, Valid}

object GCDParameter {
  implicit def rwP: upickle.default.ReadWriter[GCDParameter] =
    upickle.default.macroRW
}

/** Parameter of [[GCD]] */
case class GCDParameter(width: Int, useAsyncReset: Boolean) extends SerializableModuleParameter

/** Verification IO of [[GCD]] */
class GCDProbe(parameter: GCDParameter) extends Bundle {
  val busy = Bool()
}

/** Metadata of [[GCD]]. */
@instantiable
class GCDOM(parameter: GCDParameter) extends Class {
  val width:         Property[Int] = Output(Property[Int]())
  val useAsyncReset: Property[Boolean] = Output(Property[Boolean]())
  width := Property(parameter.width)
  useAsyncReset := Property(parameter.useAsyncReset)
}

/** Interface of [[GCD]]. */
class GCDInterface(parameter: GCDParameter) {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
  val input = Flipped(DecoupledIO(new Bundle {
    val x = UInt(parameter.width.W)
    val y = UInt(parameter.width.W)
  }))
  val output = Valid(UInt(parameter.width.W))
  val probe = Output(Probe(new GCDProbe(parameter)))
  val om = Output(Property[AnyClassType]())
}

/** Hardware Implementation of GCD */
@instantiable
class GCD(val parameter: GCDParameter)
    extends FixedIORawModule(new GCDInterface(parameter))
    with SerializableModule[GCDParameter]
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val x: UInt = Reg(chiselTypeOf(io.input.bits.x))
  val y: UInt = Reg(chiselTypeOf(io.input.bits.x))
  val busy = y === 0.U

  when(x > y) { x := x - y }.otherwise { y := y - x }

  when(io.input.fire) {
    x := io.input.bits.x
    y := io.input.bits.y
  }

  io.input.ready := !busy
  io.output.bits := x
  io.output.valid := !busy

  // Assign Probe
  val probeWire: GCDProbe = Wire(chiselTypeOf(io.probe))
  define(io.probe, ProbeValue(probeWire))
  probeWire.busy := busy

  // Assign Metadata
  val omInstance: Instance[GCDOM] = Instantiate(new GCDOM(parameter))
  io.om := omInstance.getPropertyReference.asAnyClassType
}

object GCDTestBenchParameter {
  implicit def rwP: upickle.default.ReadWriter[GCDTestBenchParameter] =
    upickle.default.macroRW
}

/** Parameter of [[GCD]]. */
case class GCDTestBenchParameter(
  testVerbatimParameter: TestVerbatimParameter,
  gcdParameter:          GCDParameter,
  timeout:               Int,
  testSize:              Int)
    extends SerializableModuleParameter {
  require(
    (testVerbatimParameter.useAsyncReset && gcdParameter.useAsyncReset) ||
      (!testVerbatimParameter.useAsyncReset && !gcdParameter.useAsyncReset),
    "Reset Type check failed."
  )
}

@instantiable
class GCDTestBenchOM(parameter: GCDTestBenchParameter) extends Class {
  val gcd = Output(Property[AnyClassType]())
  @public
  val gcdIn = Input(Property[AnyClassType]())
  gcd := gcdIn
}

class GCDTestBenchInterface(parameter: GCDTestBenchParameter) extends Bundle {
  val om = Output(Property[AnyClassType]())
}

@instantiable
class GCDTestBench(val parameter: GCDTestBenchParameter)
    extends FixedIORawModule(new GCDTestBenchInterface(parameter))
    with SerializableModule[GCDTestBenchParameter]
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = verbatim.io.clock
  override protected def implicitReset: Reset = verbatim.io.reset
  // Instantiate Drivers
  val verbatim: Instance[TestVerbatim] = Instantiate(
    new TestVerbatim(parameter.testVerbatimParameter)
  )
  // Instantiate DUT.
  val dut: Instance[GCD] = Instantiate(new GCD(parameter.gcdParameter))
  // Instantiate OM
  val omInstance = Instantiate(new GCDTestBenchOM(parameter))
  io.om := omInstance.getPropertyReference.asAnyClassType
  omInstance.gcdIn := dut.io.om

  dut.io.clock := implicitClock
  dut.io.reset := implicitReset

  // Simulation Logic
  val simulationTime: UInt = RegInit(0.U(64.W))
  simulationTime := simulationTime + 1.U
  // For each timeout cycles, check it
  RawUnclockedNonVoidFunctionCall("gcd_watchdog", UInt(8.W))(
    simulationTime === parameter.timeout.U
  )
  class TestPayload extends Bundle {
    val x = UInt(parameter.gcdParameter.width.W)
    val y = UInt(parameter.gcdParameter.width.W)
    val result = UInt(parameter.gcdParameter.width.W)
  }
  val request =
    RawUnclockedNonVoidFunctionCall("gcd_input", Valid(new TestPayload))(
      dut.io.input.ready
    )
  dut.io.input.valid := request.valid
  dut.io.input.bits := request.bits

  // LTL Checker
  import Sequence._
  AssertProperty(
    BoolSequence(request.fire) |-> eventually(dut.io.output.valid),
    label = Some("GCD_ASSERT_REQ_SHOULD_RESP")
  )
  AssertProperty(
    not(
      Sequence(
        request.fire,
        Delay(),
        request.fire,
        Delay(),
        dut.io.output.valid
      )
    ),
    label = Some("GCD_ASSERT_MULTIPLE_REQ")
  )
  AssertProperty(
    BoolSequence(
      dut.io.output.valid && (request.bits.result === dut.io.output.bits)
    ),
    label = Some("GCD_ASSERT_RESULT_CHECK")
  )
  CoverProperty(
    repeatAtLeast(BoolSequence(dut.io.input.fire), parameter.testSize),
    label = Some("GCD_COVER_FIRE")
  )
  CoverProperty(
    BoolSequence(dut.io.input.ready && !dut.io.input.valid),
    label = Some("GCD_COVER_BACK_PRESSURE")
  )
}

case class TestVerbatimParameter(
  useAsyncReset:    Boolean,
  initFunctionName: String,
  dumpFunctionName: String,
  clockFlipTick:    Int,
  resetFlipTick:    Int)

@instantiable
class TestVerbatimOM(parameter: TestVerbatimParameter) extends Class {
  val useAsyncReset:    Property[Boolean] = IO(Output(Property[Boolean]()))
  val initFunctionName: Property[String] = IO(Output(Property[String]()))
  val dumpFunctionName: Property[String] = IO(Output(Property[String]()))
  val clockFlipTick:    Property[Int] = IO(Output(Property[Int]()))
  val resetFlipTick:    Property[Int] = IO(Output(Property[Int]()))
  val gcd = IO(Output(Property[AnyClassType]()))
  @public
  val gcdIn = IO(Input(Property[AnyClassType]()))
  gcd := gcdIn
  useAsyncReset := Property(parameter.useAsyncReset)
  initFunctionName := Property(parameter.initFunctionName)
  dumpFunctionName := Property(parameter.dumpFunctionName)
  clockFlipTick := Property(parameter.clockFlipTick)
  resetFlipTick := Property(parameter.resetFlipTick)
}

/** Test blackbox for clockgen, wave dump and extra testbench-only codes. */
class TestVerbatimInterface(parameter: TestVerbatimParameter) extends Bundle {
  val clock: Clock = Output(Clock())
  val reset: Reset = Output(
    if (parameter.useAsyncReset) AsyncReset() else Bool()
  )
}

@instantiable
class TestVerbatim(parameter: TestVerbatimParameter)
    extends FixedIOExtModule(new TestVerbatimInterface(parameter))
    with HasExtModuleInline {
  setInline(
    s"$desiredName.sv",
    s"""module $desiredName(output reg clock, output reg reset);
       |  export "DPI-C" function ${parameter.dumpFunctionName};
       |  function ${parameter.dumpFunctionName}(input string file);
       |`ifdef VCS
       |    $$fsdbDumpfile(file);
       |    $$fsdbDumpvars("+all");
       |    $$fsdbDumpon;
       |`endif
       |`ifdef VERILATOR
       |    $$dumpfile(file);
       |    $$dumpvars(0);
       |`endif
       |  endfunction;
       |
       |  import "DPI-C" context function void ${parameter.initFunctionName}();
       |  initial begin
       |    ${parameter.initFunctionName}();
       |    clock = 1'b0;
       |    reset = 1'b1;
       |  end
       |  initial #(${parameter.resetFlipTick}) reset = 1'b0;
       |  always #${parameter.clockFlipTick} clock = ~clock;
       |endmodule
       |""".stripMargin
  )
}
