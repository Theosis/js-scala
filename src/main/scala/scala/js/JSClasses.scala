package scala.js

import scala.virtualization.lms.common._

import javassist._
import javassist.expr._

import java.io.PrintWriter

trait JSClasses extends JSClassProxyBase {
  trait Factory[+T] {
    def apply(args: Rep[Any]*): Rep[T]
  }
  def register[T<:AnyRef:Manifest](outer: AnyRef): Factory[T]
}

trait JSClassesExp extends JSClasses with JSClassProxyExp {
  trait Constructor[+T]

  case class MethodTemplate(name: String, params: List[Sym[Any]], body: Exp[Any])
  case class ParentTemplate(constructor: Exp[Any], instance: Exp[Any])

  case class This() extends Exp[AnyRef]

  case class ClassTemplate[T:Manifest](parent: Option[ParentTemplate], methods: List[MethodTemplate]) extends Def[Constructor[T]]
  case class New[T:Manifest](constructor: Exp[Constructor[T]], args: List[Rep[Any]]) extends Def[T]

  override def register[T<:AnyRef:Manifest](outer: AnyRef) = {
    val constructor = registerInternal[T](outer)
    new Factory[T] {
      override def apply(args: Rep[Any]*) = create[T](constructor, args.toList)
    }
  }

  private def create[T<:AnyRef:Manifest](constructor: Exp[Constructor[T]], args: List[Rep[Any]]): Exp[T] =
    reflectEffect(New(constructor, args))

  private var registered : Map[String, Exp[Constructor[Any]]] = Map()
  private def registerInternal[T<:AnyRef:Manifest](outer: AnyRef) : Exp[Constructor[T]] = {
    val m = implicitly[Manifest[T]]
    val clazz = m.erasure
    val key = clazz.getName

    registered.get(key) match {
       case Some(constructor) => return constructor.asInstanceOf[Exp[Constructor[T]]]
       case None => ()
    }

    val cp = ClassPool.getDefault
    cp.insertClassPath(new ClassClassPath(clazz))
    val cl = new Loader(cp)
    val cc = cp.get(key)

    val outerMethodName = key.replace(".", "$") + "$$$outer"
    val outerSrc = "((" + classOf[JSClassProxyExp].getName + ")" + outerMethodName + "())"
    val thisSrc = "(new " + classOf[This].getName + "((" + classOf[JSClassesExp].getName + ") " + outerMethodName + "()))"
    val reflectEffectSrc = "((" + classOf[scala.virtualization.lms.internal.Effects].getName + ")" + outerMethodName + "()).reflectEffect"
    val expName = classOf[Exp[AnyRef]].getName
    def wrapInReflectEffect(x: String) = {
      val src = reflectEffectSrc + "((" + classOf[Def[AnyRef]].getName + ") " + x + ", scala.reflect.Manifest$.MODULE$.Object())";
      src
    }
    def fieldAccess(field: String) = {
      val src = (
        "$_ = ($0 == this ? " +
        wrapInReflectEffect("new " + classOf[FieldAccess[AnyRef]].getName + "(" + outerSrc + ", " + thisSrc + ", \"" + field + "\")") + " : " +
        "($0." + field + "));")
      src
    }
    def fieldUpdate(field: String) = {
      val src = (
        "if ($0 == this) " +
        wrapInReflectEffect("new " + classOf[FieldUpdate].getName + "(" + outerSrc + ", " + thisSrc + ", \"" + field + "\", ((" + expName + ") $1))") + "; else " +
        "($0." + field + " = $1);")
      src
    }
    cc.instrument(new ExprEditor() {
      override def edit(f: expr.FieldAccess) {
        if (f.getClassName == key && f.getFieldName != "$outer") {
          f.replace(if (f.isReader) fieldAccess(f.getFieldName) else fieldUpdate(f.getFieldName))
        }
      }
    })

    // val implClazz = Class.forName(traitClazz.getName + "$class")
    // val parents = traitClazz.getInterfaces.filter(_ != implicitly[Manifest[scala.ScalaObject]].erasure)
    // assert (parents.length < 2, "Only single inheritance is supported.")
    // val parentConstructor = if (parents.length == 0) None else Some(registerInternal[AnyRef](outer)(Manifest.classType(parents(0))))
    // val parent = parentConstructor.map(c => ParentTemplate(c, create[AnyRef](c)))

    // val self = proxyTrait[T](This[T](), outer)
    // val methods = 
    //   for (method <- implClazz.getDeclaredMethods.toList)
    //     yield {
    //       val n = method.getParameterTypes.length
    //       val params = (1 to (n-1)).toList.map(_ => fresh[Any])
    //       val args = (self::params).toArray
    //       MethodTemplate(method.getName, params, reifyEffects(method.invoke(null, args: _*).asInstanceOf[Exp[Any]]))
    //     }

    // val constructor = ClassTemplate[T](parent, methods) : Exp[Constructor[T]]
    // registered = registered.updated(key, constructor)
    // constructor
    fresh[Constructor[T]]
  }

  
  override def syms(e: Any): List[Sym[Any]] = e match {
    case MethodTemplate(_, params, body) => syms(body)
    case _ => super.syms(e)
  }

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case MethodTemplate(_, params, body) => params.flatMap(syms) ::: effectSyms(body)
    case _ => super.boundSyms(e)
  }

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case MethodTemplate(_, params, body) => freqHot(body)
    case _ => super.symsFreq(e)
  }
}

trait JSGenClasses extends JSGenBase with JSGenClassProxy {
  val IR: JSClassesExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    case ClassTemplate(parentTemplate, methodTemplates) =>
      stream.println("var " + quote(sym) + " = function() {")
      parentTemplate.foreach(pt => stream.println("this.$super$ = " + quote(pt.constructor) + ".prototype"))
      stream.println("this.$init$()")
      stream.println("}")
      parentTemplate.foreach(pt => stream.println(quote(sym) + ".prototype = " + quote(pt.instance)))
      for (MethodTemplate(name, params, body) <- methodTemplates) {
	stream.println(quote(sym) + ".prototype." + name + " = function" + params.map(quote).mkString("(", ",", ")") + " {")
	emitBlock(body)
	stream.println("return " + quote(getBlockResult(body)))
	stream.println("}")
      }
    case New(constructor, args) =>
      emitValDef(sym, "new " + quote(constructor) + args.map(quote).mkString("(", ", ", ")"))
    case _ => super.emitNode(sym, rhs)
  }

  override def quote(x: Exp[Any]) : String = x match {
    case This() => "this"
    case _ => super.quote(x)
  }
}