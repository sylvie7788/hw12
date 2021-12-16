package popl.js

import scala.util.parsing.combinator._
import scala.util.parsing.input._
import ast._
import popl.js.util.JsException

import scala.util.matching.Regex

object parse extends JavaTokenParsers {
  protected override val whiteSpace: Regex = """(\s|//.*|(?m)/\*(\*(?!/)|[^*])*\*/)+""".r
  
  val reserved: Set[String] =
    Set("undefined", "true", "false", "null",
        "const", "let",  
        "function", "return",
        "Bool", "String", "Num", "Undefined", "Any")
  
  def stmt: Parser[Expr] =
    rep(basicStmt) ~ opt(lastBasicStmt) ^^
    { case (sts: List[Expr])~lst => 
        val stmts = sts ++ lst
        if (stmts == Nil) Undefined 
        else stmts reduceRight[Expr] {
          case (f @ Function(Some(x), _, _, _), st2) =>
            Decl(MConst, x, f, st2)
          case (Decl(m, v, e1, _), st2) => Decl(m, v, e1, st2)
          case (st1, st2) => BinOp(Seq, st1, st2)
        }
    }
   
  def stmtSep: Parser[String] = ";"
 
  def basicStmt: Parser[Expr] =
    stmtSep ^^^ Undefined | 
    decl <~ stmtSep |
    expr <~ stmtSep
    
  def lastBasicStmt: Parser[Expr] =
    stmtSep ^^^ Undefined |
    "{" ~> stmt <~ "}" |
    decl |
    expr
  
  def mutability: Parser[Mut] =
    "const" ^^^ MConst |
    "var" ^^^ MLet
    
  def decl: Parser[Decl] =
    positioned(
        (mutability ~ ident <~ "=") ~ expr ^^ 
        { case m~s~e => Decl(m, s, e, Undefined)})
        
  
  def expr: Parser[Expr] = commaExpr
  
  def commaExpr: Parser[Expr] =
    assignExpr ~ rep("," ~> assignExpr) ^^
      { case e~es => 
          (e :: es) reduceRight[Expr] { 
            case (e1, e2) => BinOp(Seq, e1, e2).setPos(e1.pos) 
          }
      }
  
  def assignExpr: Parser[Expr] =
    rep(leftSideExpr <~ "=") ~ condExpr ^^
    { case es~e => 
        (es foldRight e) { case (l, e) => BinOp(Assign, l, e).setPos(l.pos) } 
    } |
    condExpr
    
  def leftSideExpr: Parser[Expr] = callExpr

    
  def condExpr: Parser[Expr] =
    (orExpr <~ "?") ~ (assignExpr <~ ":") ~ assignExpr ^^
    { case e1~e2~e3 => If(e1, e2, e3).setPos(e1.pos) } |
    orExpr

  def orExpr: Parser[Expr] =
    andExpr ~ rep("||" ~> andExpr) ^^
      { case e1~es =>
        es.foldLeft(e1) { case (e1, e2) => BinOp(Or, e1, e2).setPos(e1.pos) }
      }

  def andExpr: Parser[Expr] =
    eqExpr ~ rep("&&" ~> eqExpr) ^^
      { case e1~es =>
        es.foldLeft(e1) { case (e1, e2) => BinOp(And, e1, e2).setPos(e1.pos) }
      }

  def binExpr(e: Parser[Expr], bop: Parser[Bop]): Parser[Expr] =
    e ~ rep(bop ~ e) ^^
      { case e1~opes =>
        opes.foldLeft(e1) { case (e1, op~e2) => BinOp(op, e1, e2).setPos(e1.pos) }
      }

  def eqOp: Parser[Bop] =
    "===" ^^^ Eq |
      "!==" ^^^ Ne

  def eqExpr: Parser[Expr] = binExpr(relExpr, eqOp)

  def relOp: Parser[Bop] =
    "<=" ^^^ Le |
      "<" ^^^ Lt |
      ">=" ^^^ Ge |
      ">" ^^^ Gt

  def relExpr: Parser[Expr] = binExpr(additiveExpr, relOp)

  def additiveOp: Parser[Bop] =
    "+" ^^^ Plus |
      "-" ^^^ Minus

  def additiveExpr: Parser[Expr] = binExpr(multitiveExpr, additiveOp)

  def multitiveOp: Parser[Bop] =
    "*" ^^^ Times |
      "/" ^^^ Div

  def multitiveExpr: Parser[Expr] = binExpr(unaryExpr, multitiveOp)
  
  def unaryOp: Parser[Uop] =
    "-" ^^^ UMinus |
    "!" ^^^ Not
     
  def unaryExpr: Parser[Expr] =
    positioned(unaryOp ~ primaryExpr ^^ { case uop~e => UnOp(uop, e) }) |
    callExpr
      
  def callExpr: Parser[Expr] =
    positioned(simpleCallExpr ~ opt(rep("." ~> ident ~ rep(callArgs))) ^^ {
      case e~None => e
      case e~Some(cs) => cs.foldLeft(e) {
        case (e, f~args) =>
          args.foldLeft(UnOp(FldDeref(f), e).setPos(e.pos): Expr) {
            case (e1, e2) => Call(e1, e2).setPos(e1.pos) 
          }
      }
    })
    
  def simpleCallExpr: Parser[Expr] =
    positioned("console.log(" ~> assignExpr <~ ")" ^^ Print) |  
    positioned(functionExpr ~ rep(callArgs) ^^ 
        { case e1~args => 
          args.foldLeft(e1) { case (e1, e2) => Call(e1, e2).setPos(e1.pos) } })
        
  def callArgs: Parser[List[Expr]] =
     "(" ~> rep(assignExpr <~ ",") ~ opt(assignExpr) <~ ")" ^^
       { case es~eopt => es ++ eopt }
    
  def functionExpr: Parser[Expr] =
    positioned("function" ~> opt(ident) ~ functionParams ~ opt(typAnn) ~ functionBody ^^
        { case p~params~tann~e => Function(p, params, tann, e) }) |
    primaryExpr
         
  def functionParams: Parser[Params] =
    "(" ~> params <~ ")" 
  
    
  def functionBody: Parser[Expr] =
    positioned ("{" ~> rep(basicStmt) ~ opt("return" ~> expr <~ opt(stmtSep)) <~ "}" ^^
    { case (sts: List[Expr])~lst => 
        val stmts = sts :+ (lst getOrElse Undefined)
        if (stmts == Nil) Undefined 
        else stmts reduceRight[Expr] {
          case (f @ Function(Some(x), _, _, _), st2) =>
            Decl(MConst, x, f, st2)
          case (Decl(mut, v, e1, _), st2) => Decl(mut, v, e1, st2)
          case (st1, st2) => BinOp(Seq, st1, st2)
        }
    })

  def fieldDecl: Parser[(String, (Mut, Expr))] =
    opt(mutability) ~ ident ~ (":" ~> assignExpr) ^^ { 
      case None~f~e => (f, (MLet, e))
      case Some(m)~f~e => (f, (m, e))
    }  
    
    
  def primaryExpr: Parser[Expr] = 
    literalExpr |
    positioned(ident ^^ Var) |
    "(" ~> expr <~ ")" |
    "{" ~> stmt <~ "}"
    
  override def ident: Parser[String] =
    super.ident ^? ({
      case id if !reserved(id) => id
    }, { id => s"$id is reserved." }) | 
    "$" ~> super.ident ^^ (s => "$" + s)
    
  def literalExpr: Parser[Expr] =
    positioned("true" ^^^ Bool(true)) |
    positioned("false" ^^^ Bool(false)) |
    positioned("undefined" ^^^ Undefined) |
    positioned(floatingPointNumber ^^ { d => Num(d.toDouble) }) |
    positioned(stringLiteral ^^ (s => Str(s.substring(1, s.length() - 1)))) |
    objectLiteral
    
  override def stringLiteral: Parser[String] =
    ("\""+"""([^"\p{Cntrl}\\]|\\[\\'"bfnrt]|\\[0-7]{3}|\\u[a-fA-F0-9]{2}|\\u[a-fA-F0-9]{4})*"""+"\"").r |
    ("\'"+"""([^'\p{Cntrl}\\]|\\[\\'"bfnrt]|\\[0-7]{3}|\\u[a-fA-F0-9]{2}|\\u[a-fA-F0-9]{4})*"""+"\'").r
  
  def objectLiteral: Parser[Expr] =
    positioned ("{" ~> rep(fieldDecl <~ ",") ~ opt(fieldDecl) <~ "}" ^^
        { case fs~fopt => ObjLit(Map(fs: _*) ++ fopt) }
    )
    
    /** Type expressions */    
  def typ: Parser[Typ] =
    functionTyp |
    objectTyp |
    baseTyp
    
  def baseTyp: Parser[Typ] =
    "Bool" ^^^ TBool |
    "String" ^^^ TString |
    "Num" ^^^ TNumber |
    "Undefined" ^^^ TUndefined |
    "Any" ^^^ TAny |
    "(" ~> typ <~ ")"
    
        
  def objectTyp: Parser[TObj] =
    "{" ~> typedFieldList <~ "}" ^^ 
      { tfs => 
        val m = tfs.foldLeft(Map.empty[String, (Mut, Typ)]) { case (m, (f, typ)) => m + (f -> typ)}
        TObj(m) 
      }
  
    
  def functionTyp: Parser[TFunction] =
    argTypList ~ ("=>" ~> typ) ^^
      { case txs~typ => TFunction(txs, typ) }
  
  def argTypList: Parser[List[Typ]] =
    baseTyp ^^ { t => List(t) } |
    "(" ~> rep(typ <~ ",") ~ opt(typ) <~ ")" ^^
    { case txs~txopt => txs ++ txopt }
    
  def typAnn: Parser[Typ] =
    ":" ~> typ
    
  def typedIdent: Parser[(String, Typ)] =
    ident ~ typAnn ^^ { case x~typ => (x, typ) }
    
  def typedIdentList(sep: String): Parser[List[(String, Typ)]] =
    rep(typedIdent <~ sep) ~ opt(typedIdent) ^^
    { case txs~txopt => txs ++ txopt }

  def typedField: Parser[(String, (Mut, Typ))] =
    opt(mutability) ~ ident ~ typAnn ^^ {
      case None~f~t => (f, (MLet, t))
      case Some(m)~f~t => (f, (m, t))
    }
  
  def typedFieldList: Parser[List[(String, (Mut, Typ))]] =
    rep(typedField <~ ",") ~ opt(typedField) ^^
    { case txs~txopt => txs ++ txopt }

  def params: Parser[Params] =
    rep(typedIdent <~ ",") ~ opt(typedIdent) ^^
    { case txs~txopt => txs ++ txopt } 

    
  /** utility functions */
  private def getExpr(p: ParseResult[Expr]): Expr = 
    p match {
      case Success(e, _) => 
        fv(e).headOption match {
          case Some(x) => 
            throw new JsException(s"Unknown identifier $x", e.pos)
          case None => e  
        }

      case p : NoSuccess =>
        throw new JsException(p.msg, p.next.pos)
    }
  
  def fromString(s: String): Expr = getExpr(parseAll(stmt, s))
  
  def fromFile(file: java.io.File): Expr = {
    val reader = new java.io.FileReader(file)
    val result = parseAll(stmt, StreamReader(reader))
    getExpr(result)
  }
}