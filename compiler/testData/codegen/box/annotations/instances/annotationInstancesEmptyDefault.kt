// IGNORE_BACKEND_FIR: JVM_IR
// TARGET_BACKEND: JVM_IR

// WITH_RUNTIME
// !LANGUAGE: +InstantiationOfAnnotationClasses

package a

import kotlin.reflect.KClass

enum class E { A, B }

annotation class A()

annotation class B(val a: A = A())

annotation class C(
    val i: Int = 42,
    val b: B = B(),
    val kClass: KClass<*> = B::class,
    val e: E = E.B,
    val aS: Array<String> = arrayOf("a", "b"),
    val aI: IntArray = intArrayOf(1, 2)
)

annotation class Partial(
    val i: Int = 42,
    val s: String = "foo",
    val e: E = E.A
)

fun box(): String {
    val c = C()
    assert(c.toString() == "@a.C(i=42, b=@a.B(a=@a.A()), kClass=interface a.B (Kotlin reflection is not available), e=B, aS=[a, b], aI=[1, 2])")
    val p = Partial(e = E.B, s = "bar")
    assert(p.toString() == "@a.Partial(i=42, s=bar, e=B)")
    return "OK"
}