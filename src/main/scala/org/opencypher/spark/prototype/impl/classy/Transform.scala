package org.opencypher.spark.prototype.impl.classy

import org.opencypher.spark.prototype.api.expr.{Expr, Var}
import org.opencypher.spark.prototype.api.record.{ProjectedSlotContent, RecordSlot}
import org.opencypher.spark.prototype.impl.physical.RuntimeContext

import scala.language.implicitConversions

trait Transform[T] {
  def filter(subject: T, expr: Expr): T
  def select(subject: T, fields: Set[Var]): T
  def project(subject: T, it: ProjectedSlotContent): T
  def join(subject: T, other: T)(lhs: RecordSlot, rhs: RecordSlot): T
}

object Transform {
  @inline final def apply[T](implicit instance: Transform[T]): Transform[T] = instance
}
