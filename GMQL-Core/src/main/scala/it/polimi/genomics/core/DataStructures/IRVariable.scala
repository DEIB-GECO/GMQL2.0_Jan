package it.polimi.genomics.core.DataStructures

import it.polimi.genomics.core.DataStructures.CoverParameters.CoverFlag.CoverFlag
import it.polimi.genomics.core.DataStructures.CoverParameters.CoverParam
import it.polimi.genomics.core.DataStructures.ExecutionParameters.BinningParameter
import it.polimi.genomics.core.DataStructures.GroupMDParameters.Direction._
import it.polimi.genomics.core.DataStructures.GroupMDParameters.{NoTop, TopParameter}
import it.polimi.genomics.core.DataStructures.GroupRDParameters.GroupingParameter
import it.polimi.genomics.core.DataStructures.JoinParametersRD.JoinQuadruple
import it.polimi.genomics.core.DataStructures.JoinParametersRD.RegionBuilder.RegionBuilder
import it.polimi.genomics.core.DataStructures.MetaAggregate.MetaAggregateStruct
import it.polimi.genomics.core.DataStructures.MetaGroupByCondition.MetaGroupByCondition
import it.polimi.genomics.core.DataStructures.MetaJoinCondition.MetaJoinCondition
import it.polimi.genomics.core.DataStructures.MetadataCondition.MetadataCondition
import it.polimi.genomics.core.DataStructures.RegionAggregate.{RegionFunction, RegionsToRegion, RegionsToMeta, RegionExtension}
import it.polimi.genomics.core.DataStructures.RegionCondition.{MetaAccessor, RegionCondition}
import it.polimi.genomics.core.ParsingType
import it.polimi.genomics.core.ParsingType.PARSING_TYPE
//import org.apache.log4j.Logger

/** Class for representing a GMQL variable.
  *
  * @param metaDag Dag (series of operations) to build the metadata of the variable
  * @param regionDag Dag (series of operations) to build the region data of the variable
  * @param schema the schema of the new generate variable
  */
case class IRVariable(metaDag : MetaOperator, regionDag : RegionOperator,
                      schema : List[(String, PARSING_TYPE)] = List.empty) (implicit binS : BinningParameter) {


  def SELECT (meta_con : MetadataCondition): IRVariable = {
    add_select_statement(external_meta = None, semi_join_condition = None, meta_condition = Some(meta_con), region_condition = None)
  }

  def SELECT (reg_con : RegionCondition): IRVariable = {
    add_select_statement(external_meta = None, semi_join_condition = None, meta_condition = None, region_condition = Some(reg_con))
  }

  def SELECT(meta_con : MetadataCondition, reg_con : RegionCondition) : IRVariable = {
    add_select_statement(external_meta = None, semi_join_condition = None, meta_condition = Some(meta_con), region_condition = Some(reg_con))
  }

  def SELECT(semi_con : MetaJoinCondition, meta_join_variable : IRVariable) : IRVariable = {
    add_select_statement(external_meta = Some(meta_join_variable.metaDag), semi_join_condition = Some(semi_con), meta_condition = None, region_condition = None)
  }

  def SELECT(semi_con : MetaJoinCondition, meta_join_variable : IRVariable, meta_con : MetadataCondition) : IRVariable = {
    add_select_statement(external_meta = Some(meta_join_variable.metaDag), semi_join_condition = Some(semi_con), meta_condition = Some(meta_con), region_condition = None)
  }

  def SELECT(semi_con : MetaJoinCondition, meta_join_variable : IRVariable,reg_con : RegionCondition) : IRVariable = {
    add_select_statement(external_meta = Some(meta_join_variable.metaDag), semi_join_condition = Some(semi_con), meta_condition = None, region_condition = Some(reg_con))
  }

  def SELECT(semi_con : MetaJoinCondition, meta_join_variable : IRVariable,meta_con : MetadataCondition, reg_con : RegionCondition) : IRVariable = {
    add_select_statement(external_meta = Some(meta_join_variable.metaDag), semi_join_condition = Some(semi_con), meta_condition = Some(meta_con), region_condition = Some(reg_con))
  }



  /**
   * Return a new variable that is a the SELECTION of the class variable
   * @param external_meta  The meta dataset to be used for the semi-join
   * @param semi_join_condition The semi-join condition
   * @param meta_condition the condition on meta data
   * @param region_condition the condition on region data
   * @return a new IRVariable
   */
  def add_select_statement(external_meta : Option[MetaOperator], semi_join_condition : Option[MetaJoinCondition],
                           meta_condition : Option[MetadataCondition], region_condition : Option[RegionCondition]): IRVariable ={


    var metaset_for_SelectRD : Option[MetaOperator] = None
    var new_meta_dag = this.metaDag
    var new_region_dag = this.regionDag
    if (meta_condition.isDefined) {
      new_meta_dag = new IRSelectMD(meta_condition.get, new_meta_dag)
      metaset_for_SelectRD = Some(new_meta_dag)
      if (!(semi_join_condition.isDefined || region_condition.isDefined)) {
        new_region_dag = new IRSelectRD(None,Some(new_meta_dag),this.regionDag)
      }
    }
    if (semi_join_condition.isDefined){
      new_meta_dag = new IRSemiJoin(external_meta.get, semi_join_condition.get, new_meta_dag)
      metaset_for_SelectRD = Some(new_meta_dag)
      if (!region_condition.isDefined){
        new_region_dag = new IRSelectRD(None,Some(new_meta_dag),this.regionDag)
      }
    }
    if (region_condition.isDefined){
      if (!metaset_for_SelectRD.isDefined && uses_metadata(region_condition.get)) metaset_for_SelectRD = Some(new_meta_dag)
      new_region_dag = new IRSelectRD(region_condition, metaset_for_SelectRD, regionDag)
    } else {

    }
    if (region_condition.isDefined && !meta_condition.isDefined && !semi_join_condition.isDefined) {
      new_meta_dag = this.metaDag
      new_region_dag = new IRSelectRD(region_condition, Some(new_meta_dag), this.regionDag)
    }

    new IRVariable(new_meta_dag, new_region_dag,this.schema)

  }


  def PROJECT(projected_meta : Option[List[String]] = None, extended_meta : Option[MetaAggregateStruct] = None,
              projected_values : Option[List[Int]] = None,
              extended_values : Option[List[RegionFunction]] = None): IRVariable = {

    var new_meta_dag = this.metaDag
    if (projected_meta.isDefined || extended_meta.isDefined){
      new_meta_dag = IRProjectMD(projected_meta, extended_meta, new_meta_dag)
    }
    if (projected_values.isDefined || extended_values.isDefined){
      val new_region_dag = IRProjectRD(projected_values, extended_values, this.regionDag)
      val new_schema = if (projected_values.isDefined)
        {
          for (x <- projected_values.get) yield this.schema(x)
        } else {
        this.schema
      } ++ extended_values.getOrElse(List.empty)
        .filter(_.output_name.isDefined)
        .map(x=>(x.output_name.get, ParsingType.DOUBLE))
      new IRVariable(new_meta_dag, new_region_dag, new_schema)
    } else {

      new IRVariable(new_meta_dag, this.regionDag, this.schema)
    }

  }

  def EXTEND(region_aggregates : List[RegionsToMeta]): IRVariable = {
    new IRVariable(IRUnionAggMD(this.metaDag, IRAggregateRD(region_aggregates, this.regionDag)),
      this.regionDag,
      this.schema)
  }


  /** Group by with both meta grouping and region grouping
    */
  def GROUP(meta_keys : Option[MetaGroupByCondition] = None, meta_aggregates : Option[List[RegionsToMeta]] = None, meta_group_name : String = "_group",
            region_keys : Option[List[GroupingParameter]], region_aggregates : Option[List[RegionsToRegion]]): IRVariable ={

    //only region grouping
    if (!meta_keys.isDefined && !meta_aggregates.isDefined) {
      new IRVariable(
        this.metaDag,
        IRGroupRD(region_keys, region_aggregates, this.regionDag))
    }
    //only the metadata grouping
    else if (!region_keys.isDefined && !region_aggregates.isDefined) {
      new IRVariable(
        IRGroupMD(meta_keys.get, meta_aggregates.get, meta_group_name, this.metaDag, this.regionDag),
        this.regionDag)
    }
    else{
      val new_region_dag = IRGroupRD(region_keys, region_aggregates, this.regionDag)
      val new_meta_dag = IRGroupMD(meta_keys.get,meta_aggregates.get, meta_group_name, this.metaDag, new_region_dag)
      new IRVariable (new_meta_dag, new_region_dag)

    }

  }

  /** ORDER with both metadata and region sorting
    */
  def ORDER(meta_ordering : Option[List[(String,Direction)]] = None, meta_new_attribute : String = "_group", meta_top_par : TopParameter = NoTop(),
                               region_ordering : Option[List[(Int,Direction)]], region_top_par : TopParameter = NoTop()) : IRVariable = {

    //only region ordering
    if(!meta_ordering.isDefined){
      val new_schema = this.schema ++ List(("order", ParsingType.INTEGER))
      new IRVariable(this.metaDag, IROrderRD(region_ordering.get, region_top_par, this.regionDag), new_schema)
    }
    //only metadata ordering
    else if (!region_ordering.isDefined){
      val new_meta_dag = new IROrderMD(meta_ordering.get, meta_new_attribute, meta_top_par, this.metaDag)
      val new_region_dag = new IRPurgeRD(new_meta_dag, this.regionDag)

      new IRVariable(new_meta_dag, new_region_dag, this.schema)
    }
    else {
      val new_meta_dag = IROrderMD(meta_ordering.get, meta_new_attribute, meta_top_par, this.metaDag)
      val new_region_dag = IRPurgeRD (new_meta_dag, IROrderRD(region_ordering.get, region_top_par, this.regionDag))

      val new_schema = this.schema ++ List(("order", ParsingType.INTEGER))
      new IRVariable(new_meta_dag, new_region_dag, new_schema)
    }
  }


  def COVER(flag : CoverFlag, minAcc : CoverParam, maxAcc : CoverParam, aggregates : List[RegionsToRegion], groupBy : Option[List[String]]) : IRVariable = {

    val grouping : Option[MetaGroupOperator] =
      if (groupBy.isDefined){
        Some(IRGroupBy(MetaGroupByCondition.MetaGroupByCondition(groupBy.get), this.metaDag))
      } else {
        None
      }

    val new_region_operator = IRRegionCover(flag, minAcc, maxAcc, aggregates, grouping, this.regionDag)
    new_region_operator.binSize = binS.size

    val new_meta = IRCollapseMD(grouping,this.metaDag)


    val new_schema = List(("AccIndex", ParsingType.INTEGER),
                          ("JaccardIntersect", ParsingType.DOUBLE),
                          ("JaccardResult", ParsingType.DOUBLE)) ++
                     (aggregates.map(x=>new_schema_field(x.output_name.getOrElse("unknown"),x.resType)))
                       .foldLeft(List.empty[(String,PARSING_TYPE)])(_++_)
    new IRVariable(new_meta, new_region_operator, new_schema)
  }


  def MERGE(groupBy : Option[List[String]]) : IRVariable = {
    if (!groupBy.isDefined){
      new IRVariable(IRMergeMD(this.metaDag, None), IRMergeRD(this.regionDag, None), this.schema)
    }
    else {
      val grouping = Some(IRGroupBy(MetaGroupByCondition.MetaGroupByCondition(groupBy.get), this.metaDag))
      new IRVariable(IRMergeMD(this.metaDag, grouping), IRMergeRD(this.regionDag, grouping), this.schema)
    }
  }

  //Difference A B: is translated to A.DIFFERENCE(B)
  def DIFFERENCE(condition : Option[MetaJoinCondition] = None, subtrahend : IRVariable) = {
    val meta_join_cond = if (condition.isDefined){
      SomeMetaJoinOperator(IRJoinBy(condition.get, this.metaDag, subtrahend.metaDag))
    }
    else {
      NoMetaJoinOperator(IRJoinBy(it.polimi.genomics.core.DataStructures.MetaJoinCondition.MetaJoinCondition(List.empty), this.metaDag, subtrahend.metaDag))
    }

    val new_region_dag = IRDifferenceRD(meta_join_cond, this.regionDag, subtrahend.regionDag)
    new_region_dag.binSize = binS.size
    val new_meta_dag = /*this.metaDag*/
      IRCombineMD(meta_join_cond,
        this.metaDag,
        subtrahend.metaDag,
        "left",
        "right")

    //difference does not change the schema
    new IRVariable(new_meta_dag, new_region_dag,this.schema)
  }

  def UNION(right_dataset : IRVariable, left_name : String = "left", right_name : String = "right") : IRVariable = {

    val schema_reformatting : List[Int] = for (f <- this.schema) yield {
      right_dataset.get_field_by_name(f._1).getOrElse(-1)
    }

    new IRVariable(IRUnionMD(right_dataset.metaDag, this.metaDag, left_name, right_name),
      IRUnionRD(schema_reformatting, right_dataset.regionDag, this.regionDag),
      this.schema)

  }


  def JOIN(meta_join : Option[MetaJoinCondition],
           region_join_condition : List[JoinQuadruple],
           region_builder : RegionBuilder,
           right_dataset : IRVariable,
           reference_name : Option[String] = None,
           experiment_name : Option[String] = None) : IRVariable = {

    val new_meta_join_result = if(meta_join.isDefined){
      SomeMetaJoinOperator(IRJoinBy(meta_join.get, this.metaDag, right_dataset.metaDag))
    } else {
      NoMetaJoinOperator(IRJoinBy(it.polimi.genomics.core.DataStructures.MetaJoinCondition.MetaJoinCondition(List.empty),this.metaDag, right_dataset.metaDag))
    }

    val new_meta_dag = IRCombineMD(new_meta_join_result,
      this.metaDag,
      right_dataset.metaDag,
      reference_name.getOrElse("left"),
      experiment_name.getOrElse("right"))
    val new_region_dag = IRGenometricJoin(new_meta_join_result, region_join_condition, region_builder, this.regionDag, right_dataset.regionDag)
    new_region_dag.binSize = binS.size

    val new_schema = this.schema.map(x => (reference_name.getOrElse("left")+"."+x._1,x._2)) ++
      right_dataset.schema.map(x => (experiment_name.getOrElse("right")+"."+x._1,x._2))
    new IRVariable(new_meta_dag, new_region_dag, new_schema)
  }



  def MAP(condition : Option[MetaJoinCondition.MetaJoinCondition],
          aggregates : List[RegionsToRegion],
          experiments : IRVariable,
          reference_name : Option[String] = None,
          experiment_name : Option[String] = None) = {
    apply_genometric_map(condition, aggregates, experiments,reference_name,experiment_name)
  }


  /**
   * It uses the current variable as reference and uses it to map the experiments passed as last parameters. Returns a new variable.
   * @param condition it is the metadata condition
   * @param aggregates a list of aggregates functions; can be empty
   * @param experiments the dataset to be mapped
   */
  def apply_genometric_map(condition : Option[MetaJoinCondition.MetaJoinCondition],
                           aggregates : List[RegionsToRegion],
                           experiments : IRVariable,
                           reference_name : Option[String],
                           experiment_name : Option[String]): IRVariable ={
    val new_join_result : OptionalMetaJoinOperator = if (condition.isDefined){
      SomeMetaJoinOperator(IRJoinBy(condition.get, this.metaDag, experiments.metaDag))
    } else {
      NoMetaJoinOperator(IRJoinBy(it.polimi.genomics.core.DataStructures.MetaJoinCondition.MetaJoinCondition(List.empty), this.metaDag, experiments.metaDag))
    }

    val new_meta_dag = IRCombineMD(new_join_result,
      this.metaDag,
      experiments.metaDag,
      reference_name.getOrElse("left"),
      experiment_name.getOrElse("right"))
    val new_region_dag = IRGenometricMap(new_join_result, aggregates, this.regionDag, experiments.regionDag)
    new_region_dag.binSize = binS.size

    val count_name = "count_" + reference_name.getOrElse("left") + "_" + experiment_name.getOrElse("right")

    val new_schema = this.schema ++
      (aggregates.map(x=>new_schema_field(x.output_name.getOrElse("unknown"),x.resType)))
        .foldLeft(new_schema_field(count_name, ParsingType.DOUBLE))(_++_)
    IRVariable(new_meta_dag, new_region_dag,new_schema)
  }

  def uses_metadata(rc : RegionCondition) : Boolean = {
    rc match {
      case RegionCondition.Predicate(_,_, MetaAccessor(_)) => true
      case RegionCondition.Predicate(_,_,_) => false
      case RegionCondition.NOT(x) => uses_metadata(x)
      case RegionCondition.AND(x,y) => uses_metadata(x) || uses_metadata(y)
      case RegionCondition.OR(x,y) => uses_metadata(x) || uses_metadata(y)
      case _ => false
    }
  }

  /**
   * Return a new field nested into a field, to be added to the schema of the variable
   * @param name the new name
   * @param parsType the type
   */
  def new_schema_field(name : String, parsType : PARSING_TYPE) = {
    List((name,parsType))
  }

  def get_number_of_fields = this.schema.size

  def get_field_by_name(name : String) : Option[Int] = {
    val putative_position = this.schema.indexWhere(x => x._1.equals(name))
    if (putative_position >= 0)
      Some(putative_position)
    else
      None
  }

}
