package fr.epiconcept.sparkly 

import fr.epiconcept.sparkly.storage.{Storage, WriteMode, FSNode}
import fr.epiconcept.sparkly.util.{log => l}
import org.apache.spark.sql.{SparkSession, Column, Dataset, Row, DataFrame}
 
object Command {
  def main(args: Array[String]): Unit = {
    val cmd = Map(
      "storage" -> Map(
        "cp" -> Set("from", "to")
        )
      )
    if(args == null || args.size < 1 || !cmd.contains(args(0))) 
      l.msg(s"first argument must be within ${cmd.keys}")
    else {
      val group = args(0)
      if(args == null || args.size < 2 || !cmd(group).contains(args(1)) || args.size % 2 == 1 ) 
        l.msg(s"second argument must be within ${cmd(group).keys} and followed by a list of pairs 'key' 'values' parameters, but the command ${args(0)} is followed by ${args.size -2} values")
      else {
        val command = args(1)
        val params = Seq.range(0, (args.size -2)/2).map(i => (args(i*2 + 2), args(i*2 + 3))).toMap

        if(!cmd(group)(command).subsetOf(params.keySet))
          l.msg(s"Cannot run $command, expected named parameters are ${cmd(group)(command)} and we got ${params.keySet}")
        else if(command == "cp") {
           val Seq(from, to) = Seq("from", "to").map(p => params.get(p).get).map(path => Storage.getNode(path).get)
           val force =  params.get("force").map(_.toBoolean).getOrElse(false)
           from.copyTo(to, if(force) WriteMode.overwrite else WriteMode.failIfExists)

        } else {
          throw new Exception("Not implemented @epi") 
        }
      }
    } 
  }
}
