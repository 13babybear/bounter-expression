package cn.bounter.expression.solver;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.constraints.Constraint;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.variables.IntVar;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表达式求解器
 */
public class ExpressionSolver {

    /**
     * 默认下限，0
     */
    private static final double DEFAULT_LOWER_BOUND = 0;
    /**
     * 默认上限，1万
     */
    private static final double DEFAULT_UPPER_BOUND = 10000;


    /**
     * 根据表达式和条件求解变量值
     * @param expression            表达式字符串，如：a + b = c
     * @param conditions            各变量条件约束，如：{"a": "a > 0 and a < 10", "b": [1,2,3], "c": "c <= 10"}
     * @return                      一组满足条件的变量值, 如：{"a": 1, "b": 2, "c": 3}，即 1 + 2 = 3
     */
    public static Map<String, Object> solve(String expression, Map<String, Object> conditions) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 创建模型
            Model model = new Model("MySolver");

            // 定义变量
            Map<String, IntVar> intVarMap = getIntVarMap(conditions, model);

            // 解析并添加约束
            //添加条件约束
            addConditionConstraints(model, conditions, intVarMap);
            //添加表达式约束
            if (StringUtils.hasText(expression)) {
                addExpressionConstraints(model, expression, intVarMap);
            }

            // 设置随机搜索策略
            model.getSolver().setSearch(Search.randomSearch(intVarMap.values().toArray(new IntVar[intVarMap.size()]), System.currentTimeMillis()));

            // 求解
            if (model.getSolver().solve()) {
                for (String key : intVarMap.keySet()) {
                    result.put(key, intVarMap.get(key).getValue());
                }
            } else {
                throw new RuntimeException("没有满足条件的解");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("表达式格式错误");
        }

        return result;
    }

    /**
     * 把各种条件表达式转换成标准的格式
     * @param condition         条件表达式
     * @return                  装换后的表达式
     * @author Simon 2024-05-29 V1.0.1
     */
    private static String normalizeCondition(String condition) {
        // 处理由 or 连接的多个条件
        String[] orConditions = splitConditions(condition, "\\s+or\\s+");
        StringBuilder normalizedCondition = new StringBuilder();

        for (String orCondition : orConditions) {
            if (normalizedCondition.length() > 0) {
                normalizedCondition.append(" or ");
            }
            normalizedCondition.append(normalizeAndConditions(orCondition.trim()));
        }

        return normalizedCondition.toString();
    }

    private static String[] splitConditions(String condition, String delimiter) {
        // 用于分割条件，并保留括号中的子条件
        return condition.split(delimiter + "(?![^()]*\\))");
    }

    private static String normalizeAndConditions(String condition) {
        // 处理由 and 连接的多个条件
        String[] andConditions = splitConditions(condition, "\\s+and\\s+");
        HashMap<String, Double> lowerBounds = new HashMap<>();
        HashMap<String, Double> upperBounds = new HashMap<>();
        HashMap<String, Boolean> lowerInclusive = new HashMap<>();
        HashMap<String, Boolean> upperInclusive = new HashMap<>();
        HashSet<String> processedVariables = new HashSet<>();

        for (String andCondition : andConditions) {
            normalizeSingleCondition(andCondition.trim(), lowerBounds, upperBounds, lowerInclusive, upperInclusive, processedVariables);
        }

        // 构建统一格式的条件表达式
        StringBuilder normalizedAndCondition = new StringBuilder();
        for (String variable : processedVariables) {
            double lowerBound = lowerBounds.getOrDefault(variable, DEFAULT_LOWER_BOUND);
            double upperBound = upperBounds.getOrDefault(variable, DEFAULT_UPPER_BOUND);
            boolean lowerIncl = lowerInclusive.getOrDefault(variable, false);
            boolean upperIncl = upperInclusive.getOrDefault(variable, false);

            String lowerBoundStr = String.format(lowerIncl ? "%s >= %.0f" : "%s > %.0f", variable, lowerBound);
            String upperBoundStr = String.format(upperIncl ? "%s <= %.0f" : "%s < %.0f", variable, upperBound);

            if (normalizedAndCondition.length() > 0) {
                normalizedAndCondition.append(" and ");
            }
            normalizedAndCondition.append(String.format("(%s and %s)", lowerBoundStr, upperBoundStr));
        }

        return normalizedAndCondition.toString();
    }

    private static void normalizeSingleCondition(String condition, HashMap<String, Double> lowerBounds, HashMap<String, Double> upperBounds, HashMap<String, Boolean> lowerInclusive, HashMap<String, Boolean> upperInclusive, HashSet<String> processedVariables) {
        // 定义正则表达式，匹配各种条件组合，包括负数和多字符变量
        String regex = "(\\b[a-zA-Z]+\\s*[<>]=?\\s*-?\\d+|-?\\d+\\s*[<>]=?\\s*[a-zA-Z]+\\b)";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(condition);

        while (matcher.find()) {
            String part = matcher.group(1);
            // 去掉所有空格
            part = part.replaceAll("\\s", "");
            String variable = "";
            Double lowerBound = null;
            Double upperBound = null;
            boolean isLowerInclusive = false;
            boolean isUpperInclusive = false;

            if (part.matches("[a-zA-Z]+[<>]=?-?\\d+")) {
                // 变量在左边
                variable = part.replaceAll("[<>]=?-?\\d+", "");
                if (part.contains(">=")) {
                    lowerBound = Double.parseDouble(part.split(">=")[1]);
                    isLowerInclusive = true;
                } else if (part.contains(">")) {
                    lowerBound = Double.parseDouble(part.split(">")[1]);
                    isLowerInclusive = false;
                } else if (part.contains("<=")) {
                    upperBound = Double.parseDouble(part.split("<=")[1]);
                    isUpperInclusive = true;
                } else if (part.contains("<")) {
                    upperBound = Double.parseDouble(part.split("<")[1]);
                    isUpperInclusive = false;
                }
            } else if (part.matches("-?\\d+[<>]=?[a-zA-Z]+")) {
                // 变量在右边
                variable = part.replaceAll("[^a-zA-Z]+", "");
                if (part.contains("<=")) {
                    lowerBound = Double.parseDouble(part.split("<=")[0]);
                    isLowerInclusive = true;
                } else if (part.contains("<")) {
                    lowerBound = Double.parseDouble(part.split("<")[0]);
                    isLowerInclusive = false;
                } else if (part.contains(">=")) {
                    upperBound = Double.parseDouble(part.split(">=")[0]);
                    isUpperInclusive = true;
                } else if (part.contains(">")) {
                    upperBound = Double.parseDouble(part.split(">")[0]);
                    isUpperInclusive = false;
                }
            }

            if (lowerBound != null) {
                if (lowerBounds.containsKey(variable)) {
                    lowerBounds.put(variable, Math.max(lowerBounds.get(variable), lowerBound));
                    lowerInclusive.put(variable, lowerInclusive.get(variable) || isLowerInclusive);
                } else {
                    lowerBounds.put(variable, lowerBound);
                    lowerInclusive.put(variable, isLowerInclusive);
                }
            }
            if (upperBound != null) {
                if (upperBounds.containsKey(variable)) {
                    upperBounds.put(variable, Math.min(upperBounds.get(variable), upperBound));
                    upperInclusive.put(variable, upperInclusive.get(variable) || isUpperInclusive);
                } else {
                    upperBounds.put(variable, upperBound);
                    upperInclusive.put(variable, isUpperInclusive);
                }
            }

            processedVariables.add(variable);
        }
    }

    /**
     * 解析条件，创建变量
     * @param conditions            条件
     * @param model                 模型
     * @return                      变量Map
     */
    private static Map<String, IntVar> getIntVarMap(Map<String, Object> conditions, Model model) {
        Map<String, IntVar> intVarMap = new HashMap<>(10);
        conditions.forEach((key, value) -> {
            if (value instanceof int[] arrayValue) {
                // 集合类型
                intVarMap.put(key, model.intVar(key, arrayValue));
            } else if (value instanceof String stringValue) {
                // 范围类型
                //标准化范围字符串为"a > 0 and a < 10"这样的结构
                stringValue = stringValue.replaceAll("⩾", ">=").replaceAll("⩽", "<=").replaceAll("≠", "!=");
                stringValue = normalizeCondition(stringValue);
                conditions.put(key, stringValue);

                // 匹配 a > 0 and a < 5这种范围型条件
                String regex = "(\\w+)\\s*>=?\\s*(-?\\d+)\\s*and\\s*\\1\\s*<=?\\s*(-?\\d+)";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(stringValue);

                // 获取最大最小值
                int lowerBound = 10000;
                int upperBound = 0;
                while (matcher.find()) {
                    lowerBound = Math.min(Integer.parseInt(matcher.group(2)), lowerBound); ;
                    upperBound = Math.max(Integer.parseInt(matcher.group(3)), upperBound);
                }
                intVarMap.put(key, model.intVar(key, lowerBound, upperBound));
            }
        });

        return intVarMap;
    }

    /**
     * 添加条件约束
     * @param model             模型
     * @param conditions        条件
     * @param intVarMap         变量
     */
    private static void addConditionConstraints(Model model, Map<String, Object> conditions, Map<String, IntVar> intVarMap) {
        conditions.forEach((key, value) -> {
            if (value instanceof String condition) {
                //获取变量
                IntVar var = intVarMap.get(key);

                //条件解析
                condition = condition.trim().replaceAll("[()]", "");
                if (condition.contains("or")) {
                    // 组合OR约束并添加到模型
                    Constraint[] orConstraints = getOrConstraints(condition, var, model);
                    model.or(orConstraints).post();
                } else {
                    // 组合AND约束并添加到模型
                    Constraint[] andConstraints = getAndConstraints(condition, var, model);
                    model.and(andConstraints).post();
                }
            }
        });
    }

    /**
     * 获取变量的所有or约束
     * @param orCondition       or条件字符串，如：a > 0 and a < 10 or a > 90 and a < 100
     * @param var               变量
     * @return                  or约束数组，如：[a > 0 and a < 10, a > 90 and a < 100]
     */
    private static Constraint[] getOrConstraints(String orCondition, IntVar var, Model model) {
        // 拆分OR表达式
        String[] orArray = orCondition.split("or");
        Constraint[] orConstraints = new Constraint[orArray.length];
        for (int i = 0; i < orArray.length; i++) {
            // 拆分AND表达式
            Constraint[] andConstraints = getAndConstraints(orArray[i].trim(), var, model);
            // 组合AND约束
            orConstraints[i] = model.and(andConstraints);
        }
        return orConstraints;
    }

    /**
     * 获取变量的所有and约束
     * @param andCondition      and条件字符串，如：a > 0 and a < 10
     * @param var               变量
     * @return                  and约束数组，如：[a > 0, a < 10]
     */
    private static Constraint[] getAndConstraints(String andCondition, IntVar var, Model model) {
        // 拆分AND表达式
        String[] andArray = andCondition.split("and");
        Constraint[] andConstraints = new Constraint[andArray.length];
        // 解析拆分后的每个最小约束，如：a > 0
        for (int i = 0; i < andArray.length; i++) {
            andConstraints[i] = parseCondition(model, var, andArray[i].trim());
        }
        return andConstraints;
    }

    /**
     * 把条件字符串解析成约束
     * @param model             模型
     * @param var               变量
     * @param condition         条件字符串
     * @return                  约束
     */
    private static Constraint parseCondition(Model model, IntVar var, String condition) {
        String[] parts = condition.split(" ");
        String operator = parts[1];
        int value = Integer.parseInt(parts[2]);

        switch (operator) {
            case ">":
                return model.arithm(var, ">", value);
            case ">=":
                return model.arithm(var, ">=", value);
            case "<":
                return model.arithm(var, "<", value);
            case "<=":
                return model.arithm(var, "<=", value);
            default:
                throw new IllegalArgumentException("非法运算符: " + operator);
        }
    }

    /**
     * 添加表达式约束
     * @param model             模型
     * @param expression        表达式字符串
     * @param intVarMap         变量
     * @author Simon 2024-05-29 V1.0.1
     */
    private static void addExpressionConstraints(Model model, String expression, Map<String, IntVar> intVarMap) {
        //表达式预处理，统一表达式格式
        expression = expression.replaceAll("⩾", ">=").replaceAll("⩽", "<=").replaceAll("≠", "!=");

        //解析
        parseLogicalExpression(model, expression, intVarMap).post();
    }

    /**
     * 递归解析逻辑表达式，处理括号和逻辑运算符
     * @param model
     * @param expression
     * @param intVarMap
     * @return
     */
    private static Constraint parseLogicalExpression(Model model, String expression, Map<String, IntVar> intVarMap) {
        expression = expression.trim();

        // 通过计数来平衡括号对，如果表达式被一对括号包裹，且内部的括号是平衡的，则去除外层括号
        if (expression.startsWith("(") && expression.endsWith(")")) {
            int count = 0;
            boolean balanced = true;
            for (int i = 0; i < expression.length(); i++) {
                if (expression.charAt(i) == '(') {
                    count++;
                }
                if (expression.charAt(i) == ')') {
                    count--;
                }
                // 如果在去掉最后一个括号之前就达到了平衡，那么不应去掉外层括号
                if (count == 0 && i < expression.length() - 1) {
                    balanced = false;
                    break;
                }
            }
            if (balanced) {
                //去除外层括号
                return parseLogicalExpression(model, expression.substring(1, expression.length() - 1), intVarMap);
            }
        }

        //把 and、or联合的表达式拆分成子表达式递归处理
        if (expression.contains("and") || expression.contains("or")) {
            String[] parts = splitByTopLevelOperator(expression, "and");
            if (parts.length == 1) {
                parts = splitByTopLevelOperator(expression, "or");
                Constraint[] constraints = new Constraint[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    constraints[i] = parseLogicalExpression(model, parts[i], intVarMap);
                }
                return model.or(constraints);
            } else {
                Constraint[] constraints = new Constraint[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    constraints[i] = parseLogicalExpression(model, parts[i], intVarMap);
                }
                return model.and(constraints);
            }
        }

        String regex = "(<=|>=|!=|=|<|>)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(expression);
        if (!matcher.find()) {
            throw new RuntimeException("表达式错误");
        }
        String op = matcher.group(1);
        String[] exprArray = expression.split(op);
        IntVar leftIntVar = parseArithmeticExpression(model, intVarMap, exprArray[0].trim());
        IntVar rightIntVar = parseArithmeticExpression(model, intVarMap, exprArray[1].trim());
        return model.arithm(leftIntVar, op, rightIntVar);
    }

    /**
     * 按顶级运算符（“and”或“or”）拆分表达式，处理括号嵌套
     * @param expression
     * @param operator
     * @return
     */
    private static String[] splitByTopLevelOperator(String expression, String operator) {
        Stack<Character> stack = new Stack<>();
        int lastSplit = 0;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (ch == '(') {
                stack.push(ch);
            } else if (ch == ')') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            } else if (stack.isEmpty()) {
                if (expression.substring(i).startsWith(operator)) {
                    parts.add(expression.substring(lastSplit, i).trim());
                    lastSplit = i + operator.length();
                    i += operator.length() - 1;
                }
            }
        }
        parts.add(expression.substring(lastSplit).trim());
        return parts.toArray(new String[0]);
    }

    /**
     * 使用递归下降解析法解析算术表达式
     * @param model
     * @param intVarMap
     * @param expression
     * @return
     */
    private static IntVar parseArithmeticExpression(Model model, Map<String, IntVar> intVarMap, String expression) {
        expression = expression.trim();
        if (expression.startsWith("(") && expression.endsWith(")")) {
            return parseArithmeticExpression(model, intVarMap, expression.substring(1, expression.length() - 1));
        }
        Stack<IntVar> varStack = new Stack<>();
        Stack<Character> opStack = new Stack<>();
        int i = 0;
        while (i < expression.length()) {
            char ch = expression.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (Character.isLetterOrDigit(ch)) {
                StringBuilder sb = new StringBuilder();
                while (i < expression.length() && (Character.isLetterOrDigit(expression.charAt(i)) || expression.charAt(i) == '_')) {
                    sb.append(expression.charAt(i));
                    i++;
                }
                String token = sb.toString();
                if (Character.isDigit(token.charAt(0))) {
                    varStack.push(model.intVar(Integer.parseInt(token)));
                } else {
                    varStack.push(intVarMap.get(token));
                }
                continue;
            }
            if (ch == '+' || ch == '-' || ch == '*' || ch == '/') {
                while (!opStack.isEmpty() && precedence(opStack.peek()) >= precedence(ch)) {
                    char op = opStack.pop();
                    IntVar right = varStack.pop();
                    IntVar left = varStack.pop();
                    varStack.push(applyOperation(model, left, right, op));
                }
                opStack.push(ch);
            } else if (ch == '(') {
                opStack.push(ch);
            } else if (ch == ')') {
                while (opStack.peek() != '(') {
                    char op = opStack.pop();
                    IntVar right = varStack.pop();
                    IntVar left = varStack.pop();
                    varStack.push(applyOperation(model, left, right, op));
                }
                opStack.pop(); // pop '('
            }
            i++;
        }
        while (!opStack.isEmpty()) {
            char op = opStack.pop();
            IntVar right = varStack.pop();
            IntVar left = varStack.pop();
            varStack.push(applyOperation(model, left, right, op));
        }
        return varStack.pop();
    }

    /**
     * 计算操作符优先级
     * @param op
     * @return
     */
    private static int precedence(char op) {
        switch (op) {
            case '+':
            case '-':
                return 1;
            case '*':
            case '/':
                return 2;
            default:
                return -1;
        }
    }

    /**
     * 执行操作符运算
     * @param model
     * @param left
     * @param right
     * @param op
     * @return
     */
    private static IntVar applyOperation(Model model, IntVar left, IntVar right, char op) {
        switch (op) {
            case '+':
                return left.add(right).intVar();
            case '-':
                return left.sub(right).intVar();
            case '*':
                return left.mul(right).intVar();
            case '/':
                return left.div(right).intVar();
            default:
                throw new IllegalArgumentException("非法运算符: " + op);
        }
    }
}
