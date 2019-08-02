package io.github.iamazy.elasticsearch.dsl.sql.parser.query.exact;

import io.github.iamazy.elasticsearch.dsl.antlr4.ElasticsearchParser;
import io.github.iamazy.elasticsearch.dsl.sql.enums.SqlConditionOperator;
import io.github.iamazy.elasticsearch.dsl.sql.exception.ElasticSql2DslException;
import io.github.iamazy.elasticsearch.dsl.sql.model.AtomicQuery;
import io.github.iamazy.elasticsearch.dsl.sql.parser.query.fulltext.LikeQueryParser;
import io.github.iamazy.elasticsearch.dsl.sql.parser.query.geo.GeoQueryParser;
import io.github.iamazy.elasticsearch.dsl.sql.parser.query.join.JoinQueryParser;
import io.github.iamazy.elasticsearch.dsl.sql.parser.query.nested.NestedQueryParser;
import org.elasticsearch.index.query.*;

/**
 * @author iamazy
 * @date 2019/7/29
 * @descrition
 **/
public class BinaryQueryParser extends AbstractExactQueryParser {

    private final GeoQueryParser geoQueryParser;
    private final JoinQueryParser joinQueryParser;
    private final LikeQueryParser likeQueryParser;
    private final InListQueryParser inListQueryParser;
    private final NestedQueryParser nestedQueryParser;
    public BinaryQueryParser(){
        geoQueryParser=new GeoQueryParser();
        joinQueryParser=new JoinQueryParser();
        likeQueryParser=new LikeQueryParser();
        inListQueryParser=new InListQueryParser();
        nestedQueryParser=new NestedQueryParser();
    }


    public AtomicQuery parseBinaryQuery(ElasticsearchParser.BinaryContext binaryContext) {
        int operatorType = binaryContext.operator.getType();
        //EQ NEQ AEQ
        // Will any one query by `not approximately equal` ?  no !!!
        if (operatorType == ElasticsearchParser.EQ || operatorType == ElasticsearchParser.NE || operatorType == ElasticsearchParser.AEQ) {
            String targetVal = binaryContext.rightExpr.getText();
            SqlConditionOperator operator;
            switch (operatorType) {
                case ElasticsearchParser.NE: {
                    operator = SqlConditionOperator.NotEqual;
                    break;
                }
                case ElasticsearchParser.AEQ: {
                    operator = SqlConditionOperator.ApproximatelyEqual;
                    break;
                }
                default:
                case ElasticsearchParser.EQ: {
                    operator = SqlConditionOperator.Equality;
                    break;
                }
            }
            return parseCondition(binaryContext.leftExpr, operator, new Object[]{targetVal}, (fieldName, operator1, rightParams) -> {
                switch (operator1) {
                    case NotEqual: {
                        return QueryBuilders.boolQuery().mustNot(QueryBuilders.termQuery(fieldName, rightParams[0]));
                    }
                    case ApproximatelyEqual: {
                        return QueryBuilders.matchQuery(fieldName, rightParams[0]);
                    }
                    default:
                    case Equality: {
                        return QueryBuilders.termQuery(fieldName, rightParams[0]);
                    }
                }
            });
        }
        // AND OR && ||
        else if (operatorType == ElasticsearchParser.AND ||
                operatorType == ElasticsearchParser.BOOLAND ||
                operatorType == ElasticsearchParser.OR ||
                operatorType == ElasticsearchParser.BOOLOR) {
            ElasticsearchParser.ExpressionContext leftExpr = binaryContext.leftExpr;
            ElasticsearchParser.ExpressionContext rightExpr = binaryContext.rightExpr;
            if (binaryContextMatch(leftExpr)&&binaryContextMatch(rightExpr)) {
                AtomicQuery leftQuery=parseExpressionContext(leftExpr);
                AtomicQuery rightQuery=parseExpressionContext(rightExpr);

                BoolQueryBuilder boolQueryBuilder;
                if (operatorType == ElasticsearchParser.AND || operatorType == ElasticsearchParser.BOOLAND) {
                    boolQueryBuilder = QueryBuilders.boolQuery().must(leftQuery.getQueryBuilder())
                            .must(rightQuery.getQueryBuilder());
                } else {
                    boolQueryBuilder = QueryBuilders.boolQuery().should(leftQuery.getQueryBuilder())
                            .should(rightQuery.getQueryBuilder());
                }
                return new AtomicQuery(boolQueryBuilder);
            } else {
                throw new ElasticSql2DslException("expressions on  both sides of [and or && ||] must be binary context");
            }
        }
        // GT GTE LT LTE
        else if (operatorType == ElasticsearchParser.GT ||
                operatorType == ElasticsearchParser.GTE ||
                operatorType == ElasticsearchParser.LT ||
                operatorType == ElasticsearchParser.LTE) {
            SqlConditionOperator operator;
            switch (operatorType) {
                case ElasticsearchParser.GT: {
                    operator = SqlConditionOperator.GreaterThan;
                    break;
                }
                case ElasticsearchParser.GTE: {
                    operator = SqlConditionOperator.GreaterThanOrEqual;
                    break;
                }
                case ElasticsearchParser.LT: {
                    operator = SqlConditionOperator.LessThan;
                    break;
                }
                default:
                case ElasticsearchParser.LTE: {
                    operator = SqlConditionOperator.LessThanOrEqual;
                    break;
                }
            }
            String targetVal = binaryContext.rightExpr.getText();
            return parseCondition(binaryContext.leftExpr, operator, new Object[]{targetVal}, (fieldName, operator1, rightParams) -> {
                RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery(fieldName);
                switch (operator1) {
                    case GreaterThan: {
                        return rangeQuery.gt(rightParams[0]);
                    }
                    case GreaterThanOrEqual: {
                        return rangeQuery.gte(rightParams[0]);
                    }
                    case LessThan: {
                        return rangeQuery.lt(rightParams[0]);
                    }
                    default:
                    case LessThanOrEqual: {
                        return rangeQuery.lte(rightParams[1]);
                    }
                }
            });
        }
        //IS | IS NOT
        else if (binaryContext.isClause() != null) {
            ElasticsearchParser.IsClauseContext isClauseContext = binaryContext.isClause();
            return parseIsContext(isClauseContext);
        }
        //LIKE | NOT LIKE
        else if (binaryContext.likeClause() != null) {
            ElasticsearchParser.LikeClauseContext likeClauseContext = binaryContext.likeClause();
            return likeQueryParser.parse(likeClauseContext);
        }
        throw new ElasticSql2DslException("[syntax error] not supported yet");
    }


    private AtomicQuery parseIsContext(ElasticsearchParser.IsClauseContext isContext){
        SqlConditionOperator operator = isContext.not == null ? SqlConditionOperator.IsNull : SqlConditionOperator.IsNotNull;
        return parseCondition(isContext.field, operator, null, (fieldName, operator1, rightParams) -> {
            ExistsQueryBuilder existsQueryBuilder = QueryBuilders.existsQuery(fieldName);
            switch (operator1) {
                case IsNull: {
                    return QueryBuilders.boolQuery().mustNot(existsQueryBuilder);
                }
                default:
                case IsNotNull: {
                    return existsQueryBuilder;
                }
            }
        });
    }

    private AtomicQuery parseLrExprContext(ElasticsearchParser.LrExprContext lrExprContext) {
        ElasticsearchParser.ExpressionContext expression = lrExprContext.expression();
        if (expression instanceof ElasticsearchParser.BinaryContext) {
            return parseBinaryQuery((ElasticsearchParser.BinaryContext) expression);
        } else if (expression instanceof ElasticsearchParser.LrExprContext) {
            return parseLrExprContext((ElasticsearchParser.LrExprContext) expression);
        }  else if(expression instanceof ElasticsearchParser.GeoContext){
            return geoQueryParser.parse((ElasticsearchParser.GeoContext) expression);
        }else if(expression instanceof ElasticsearchParser.JoinContext){
            return joinQueryParser.parse((ElasticsearchParser.JoinContext) expression);
        }else if(expression instanceof ElasticsearchParser.InContext){
            return inListQueryParser.parseInListQuery((ElasticsearchParser.InContext) expression);
        }else if(expression instanceof ElasticsearchParser.NestedContext){
            return nestedQueryParser.parse((ElasticsearchParser.NestedContext) expression);
        }
        throw new ElasticSql2DslException("LrExprContext must be instance of BinaryContext or LrExprContext");
    }

    private AtomicQuery parseExpressionContext(ElasticsearchParser.ExpressionContext expressionContext){
        if (expressionContext instanceof ElasticsearchParser.BinaryContext) {
            ElasticsearchParser.BinaryContext leftBinary = (ElasticsearchParser.BinaryContext) expressionContext;
            return parseBinaryQuery(leftBinary);
        } else if(expressionContext instanceof ElasticsearchParser.LrExprContext){
            ElasticsearchParser.LrExprContext leftLrExpr = (ElasticsearchParser.LrExprContext) expressionContext;
            return parseLrExprContext(leftLrExpr);
        }else if(expressionContext instanceof ElasticsearchParser.GeoContext){
            ElasticsearchParser.GeoContext geoContext=(ElasticsearchParser.GeoContext)expressionContext;
            return geoQueryParser.parse(geoContext);
        }else if(expressionContext instanceof ElasticsearchParser.JoinContext){
            return joinQueryParser.parse((ElasticsearchParser.JoinContext) expressionContext);
        }else if(expressionContext instanceof ElasticsearchParser.InContext){
            return inListQueryParser.parseInListQuery((ElasticsearchParser.InContext) expressionContext);
        }else if(expressionContext instanceof ElasticsearchParser.NestedContext){
            return nestedQueryParser.parse((ElasticsearchParser.NestedContext) expressionContext);
        }
        else{
            throw new ElasticSql2DslException("not support yet");
        }
    }

    private boolean binaryContextMatch(ElasticsearchParser.ExpressionContext expressionContext){
        if(expressionContext instanceof ElasticsearchParser.BinaryContext){
            return true;
        }
        if(expressionContext instanceof ElasticsearchParser.LrExprContext){
            return true;
        }
        if(expressionContext instanceof ElasticsearchParser.GeoContext){
            return true;
        }
        if(expressionContext instanceof ElasticsearchParser.NestedContext){
            return true;
        }
        return expressionContext instanceof ElasticsearchParser.JoinContext;
    }
}
