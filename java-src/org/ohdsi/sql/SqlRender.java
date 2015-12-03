package org.ohdsi.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

public class SqlRender {

	private static class Span {
		public int		start;
		public int		end;
		public boolean	valid;

		public Span(int start, int end) {
			this.start = start;
			this.end = end;
			this.valid = true;
		}
	}

	private static class IfThenElse {
		public Span		condition;
		public Span		ifTrue;
		public Span		ifFalse;
		public boolean	hasIfFalse	= false;

		public int start() {
			return condition.start;
		}

		int end() {
			return hasIfFalse ? ifFalse.end : ifTrue.end;
		}
	}

    	private static class ForEach {
	    public Span collection;
	    public Span statement;

	    public int start() { return collection.start; }

	    int end() { return statement.end; }
	}

	private static List<Span> findCurlyBracketSpans(String str) {
		Stack<Integer> starts = new Stack<Integer>();
		List<Span> spans = new ArrayList<Span>();
		for (int i = 0; i < str.length(); i++) {
			if (str.charAt(i) == '{') {
				starts.push(i);
			} else if (str.charAt(i) == '}') {
				if (!starts.empty()) {
					spans.add(new Span(starts.pop(), i + 1));
				}
			}
		}
		return spans;
	}

	private static List<Span> findParentheses(String str) {
		Stack<Integer> starts = new Stack<Integer>();
		List<Span> spans = new ArrayList<Span>();
		for (int i = 0; i < str.length(); i++) {
			if (str.charAt(i) == '(') {
				starts.push(i);
			} else if (str.charAt(i) == ')') {
				if (!starts.empty()) {
					spans.add(new Span(starts.pop(), i + 1));
				}
			}
		}
		return spans;
	}

	private static List<IfThenElse> linkIfThenElses(String str, List<Span> spans) {
		List<IfThenElse> ifThenElses = new ArrayList<IfThenElse>();
		if (spans.size() > 1)
			for (int i = 0; i < spans.size() - 1; i++)
				for (int j = i + 1; j < spans.size(); j++)
					if (spans.get(j).start > spans.get(i).end) {
						String inBetween = str.substring(spans.get(i).end, spans.get(j).start);
						inBetween = inBetween.trim();
						if (inBetween.equals("?")) {
							IfThenElse ifThenElse = new IfThenElse();
							ifThenElse.condition = spans.get(i);
							ifThenElse.ifTrue = spans.get(j);
							if (j < spans.size()) {
								for (int k = j + 1; k < spans.size(); k++)
									if (spans.get(k).start > spans.get(j).end) {
										inBetween = str.substring(spans.get(j).end, spans.get(k).start);
										inBetween = inBetween.trim();
										if (inBetween.equals(":")) {
											ifThenElse.ifFalse = spans.get(k);
											ifThenElse.hasIfFalse = true;
										}
									}

							}
							ifThenElses.add(ifThenElse);
							// System.out.println("Cond: " + str.substring(ifThenElse.condition.start + 1, ifThenElse.condition.end - 1));
							// System.out.println("ifTrue: " + str.substring(ifThenElse.ifTrue.start + 1, ifThenElse.ifTrue.end - 1));
							// if (ifThenElse.hasIfFalse)
							// System.out.println("ifFalse: " + str.substring(ifThenElse.ifFalse.start + 1, ifThenElse.ifFalse.end - 1));
						}
					}
		return ifThenElses;
	}

    private static ForEach findNextForEach(String str) {
        List<Span> spans = findCurlyBracketSpans(str);
//        List<ForEach> forEachs = new ArrayList<ForEach>();
        if (spans.size() > 1)
	  for (int i = 0; i < spans.size() - 1; i++) {
	      String collectionClause = str.substring(spans.get(i).start, spans.get(i).end).trim();
//	      System.err.println(collectionClause);
	      if (collectionClause.startsWith("{foreach")) {
		for (int j = i + 1; j < spans.size(); j++) {
		    if (spans.get(j).start > spans.get(i).end) {
		        String inBetween = str.substring(spans.get(i).end, spans.get(j).start);
		        inBetween = inBetween.trim();
		        if (inBetween.equals(":")) {
			  ForEach forEach = new ForEach();
			  forEach.collection = spans.get(i);
			  forEach.statement = spans.get(j);
			  return forEach;
//   							if (j < spans.size()) {
//   								for (int k = j + 1; k < spans.size(); k++)
//   									if (spans.get(k).start > spans.get(j).end) {
//   										inBetween = str.substring(spans.get(j).end, spans.get(k).start);
//   										inBetween = inBetween.trim();
//   										if (inBetween.equals(":")) {
//   											ifThenElse.ifFalse = spans.get(k);
//   											ifThenElse.hasIfFalse = true;
//   										}
//   									}
//
//   							}
//			  forEachs.add(forEach);
		        }
		    }
		}
	      }
	  }
        return null;
    }

	private static boolean evaluateCondition(String str) {
		str = str.trim();
		List<Span> spans = findParentheses(str);
		// Spans are in order of closing parenthesis, so if we go from first to last we'll always process nested parentheses first
		for (Span span : spans)
			if (!precededByIn(span.start, str)) {
				boolean evaluation = evaluateBooleanCondition(str.substring(span.start + 1, span.end - 2));
				str = StringUtils.replaceCharAt(str, span.start, evaluation ? '1' : '0');
				replace(str, spans, span.start, span.end, span.start, span.start);
			}
		return evaluateBooleanCondition(str);
	}

	private static boolean evaluateBooleanCondition(String str) {
		str = str.trim();
		int found = str.indexOf("&");
		if (found != -1) {
			String[] parts = str.split("&");
			for (String part : parts)
				if (!evaluatePrimitiveCondition(part))
					return false;

			return true;
		}
		found = str.indexOf("|");
		if (found != -1) {
			String[] parts = str.split("\\|");
			for (String part : parts)
				if (evaluatePrimitiveCondition(part))
					return true;
			return false;
		}
		return evaluatePrimitiveCondition(str);
	}

	private static boolean precededByIn(int start, String str) {
		str = str.toLowerCase();
		int matched = 0;
		for (int i = start - 1; i >= 0; i--) {
			if (!Character.isWhitespace(str.charAt(i))) {
				if (matched == 0 && str.charAt(i) == 'n')
					matched++;
				else if (matched == 1 && str.charAt(i) == 'i')
					matched++;
				else
					return false;
			} else if (matched == 2)
				return true;
		}
		return false;
	}

	private static String removeParentheses(String s) {
		if (s.length() > 1 && ((s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') || (s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')))
			return s.substring(1, s.length() - 1);
		else
			return s;
	}

	private static boolean evaluatePrimitiveCondition(String str) {
		str = str.trim();
		String str_lc = str.toLowerCase();
		if (str_lc.equals("false") || str_lc.equals("0") || str_lc.equals("!true") || str_lc.equals("!1"))
			return false;
		if (str_lc.equals("true") || str_lc.equals("1") || str_lc.equals("!false") || str_lc.equals("!0"))
			return true;

		int found = str.indexOf("==");
		if (found != -1) {
			String left = str.substring(0, found);
			left = left.trim();
			left = removeParentheses(left);
			String right = str.substring(found + 2, str.length());
			right = right.trim();
			right = removeParentheses(right);
			return (left.equals(right));
		}
		found = str.indexOf("!=");
		if (found == -1)
			found = str.indexOf("<>");
		if (found != -1) {
			String left = str.substring(0, found);
			left = left.trim();
			left = removeParentheses(left);
			String right = str.substring(found + 2, str.length());
			right = right.trim();
			right = removeParentheses(right);
			return (!left.equals(right));
		}
		found = str_lc.indexOf(" in ");
		if (found != -1) {
			String left = str.substring(0, found);
			left = left.trim();
			left = removeParentheses(left);
			String right = str.substring(found + 4, str.length());
			right = right.trim();
			if (right.length() > 2 && right.charAt(0) == '(' && right.charAt(right.length() - 1) == ')') {
				right = right.substring(1, right.length() - 1);
				String[] parts = right.split(",");
				for (String part : parts) {
					String partString = removeParentheses(part);
					if (left.equals(partString))
						return true;
				}
				return false;
			}
		}
		return true;
	}

	private static String replace(String str, List<Span> spans, int toReplaceStart, int toReplaceEnd, int replaceWithStart, int replaceWithEnd) {
		String replaceWithString = str.substring(replaceWithStart, replaceWithEnd + 1);
		str = StringUtils.replace(str, toReplaceStart, toReplaceEnd, replaceWithString);
		for (Span span : spans)
			if (span.valid) {
				if (span.start > toReplaceStart) {
					if (span.start >= replaceWithStart && span.start < replaceWithEnd) {
						int delta = toReplaceStart - replaceWithStart;
						span.start += delta;
						span.end += delta;
					} else if (span.start > toReplaceEnd) {
						int delta = toReplaceStart - toReplaceEnd + replaceWithString.length();
						span.start += delta;
						span.end += delta;
					} else {
						span.valid = false;
					}
				} else if (span.end > toReplaceEnd) {
					int delta = toReplaceStart - toReplaceEnd + replaceWithString.length();
					span.end += delta;
				}
			}
		return str;
	}

	private static Map<String, String> extractDefaults(String str) {
		// Find all spans containing defaults
		Map<String, String> defaults = new HashMap<String, String>();
		int defaultStart = 0;
		int defaultEnd = 0;
		String pre = "{DEFAULT ";
		String post = "}";
		while (defaultStart != -1 && defaultEnd != -1) {
			defaultStart = str.indexOf(pre, defaultEnd);
			if (defaultStart != -1) {
				defaultEnd = str.indexOf(post, defaultStart + pre.length());
				if (defaultEnd != -1) {
					String span = str.substring(defaultStart + pre.length(), defaultEnd);
					int found = span.indexOf("=");
					if (found != -1) {
						String parameter = span.substring(0, found);
						parameter = parameter.trim();
						if (parameter.length() > 0 && parameter.charAt(0) == '@')
							parameter = parameter.substring(1);
						String defaultValue = span.substring(found + 2, span.length());
						defaultValue = defaultValue.trim();
						defaultValue = removeParentheses(defaultValue);
						defaults.put(parameter, defaultValue);
					}
				}
			}
		}
		return defaults;
	}

    	private static class ForEachCollection {
	    Map<String, String> parameterMap;
	    List<String> tokens;
	    int count;

	    ForEachCollection(Map<String, String> parameterMap, List<String> tokens, int count) {
	        this.parameterMap = parameterMap;
	        this.tokens = tokens;
	        this.count = count;
	    }
	}

    	private static ForEachCollection expandForEachLists(String str, ForEach forEach, Map<String, String> parameterMap) {


	    System.err.println("FOREACH: " + str.substring(forEach.collection.start, forEach.statement.end));
	    System.err.println("\t" + str.substring(forEach.collection.start, forEach.collection.end));
	    System.err.println("\t" + str.substring(forEach.statement.start, forEach.statement.end));


	    int count = 0;
	    Map<String, String> collectionMap = new HashMap<String, String>();
	    List<String> collectionTokens = new ArrayList<String>();
	    String collection = str.substring(forEach.collection.start, forEach.collection.end);
//	    System.err.println("BEFORE: " + collection);

	    // Must start with "{FOREACH" and end with "}"
	    collection = collection.trim();
	    collection = collection.substring(8);
	    collection = collection.substring(0, collection.length() - 1);
	    //collection = removeParentheses(collection);
	    List<String> names = StringUtils.safeSplit(collection, ',');
//	    System.err.println("HELLO");

	    List<String[]> allValues = new ArrayList<String[]>();

	    System.err.println("LOOP: " + names.size());
	    for (int i = 0; i < names.size(); ++i) {
	        System.err.println("NAME: " + names.get(i));
	        names.set(i, names.get(i).trim().substring(1));
//
	        collectionTokens.add(names.get(i));

	        String[] values = parameterMap.get(names.get(i)).split(",");
	        for (int j = 0; j < values.length; ++j) {
		  values[j] = values[j].trim();
	        }
	        if (count < values.length) {
		  count = values.length; // Take maximum length
	        }

	        allValues.add(values);
	    }

	    for (int i = 0; i < names.size(); ++i) {
	        String name = names.get(i);
	        String[] values = allValues.get(i);
	        for (int j = 0; j < count; ++j) {
		  String value = values[j % values.length]; // Cycle through values
		  collectionMap.put((name + "_" + j), value);
	        }
	    }

//	    System.err.println("AFTER: " + collection);
//
//	    System.err.println("TOKENS:");
//	    for (String token : collectionTokens) {
//	        System.err.println("\t" + token);
//	    }
//	    System.err.println("PARAMETERS:");
//	    for (Map.Entry<String, String> x : collectionMap.entrySet()) {
//	        System.err.println("\t" + x.getKey() + " -> " + x.getValue());
//	    }
//	    System.err.println("");

	    return new ForEachCollection(collectionMap, collectionTokens, count);
	}

    	private static String parseParameterDefaults(String string, Map<String, String> parameterToValue) {
	    Map<String, String> defaults = extractDefaults(string);
	    string = removeDefaults(string);
	    for (Map.Entry<String, String> pair : defaults.entrySet())
	        if (!parameterToValue.containsKey(pair.getKey()))
		  parameterToValue.put(pair.getKey(), pair.getValue());
	    return string;
	}

	private static String substituteParameters(String string, Map<String, String> parameterToValue) {
//		Map<String, String> defaults = extractDefaults(string);
//		string = removeDefaults(string);
//		for (Map.Entry<String, String> pair : defaults.entrySet())
//			if (!parameterToValue.containsKey(pair.getKey()))
//				parameterToValue.put(pair.getKey(), pair.getValue());

		// Sort parameters from longest to shortest so if one parameter name is a substring of another, it won't go wrong:
		List<Map.Entry<String, String>> sortedParameterToValue = new ArrayList<Map.Entry<String, String>>(parameterToValue.entrySet());
		Collections.sort(sortedParameterToValue, new Comparator<Map.Entry<String, String>>() {

			@Override
			public int compare(Entry<String, String> o1, Entry<String, String> o2) {
				int l1 = o1.getKey().length();
				int l2 = o2.getKey().length();
				if (l1 > l2)
					return -1;
				else if (l1 < l2)
					return 1;
				return 0;
			}
		});
		for (Map.Entry<String, String> pair : sortedParameterToValue)
			string = string.replaceAll("@" + pair.getKey(), pair.getValue());
		return string;
	}

	private static String removeDefaults(String string) {
		return string.replaceAll("\\{DEFAULT[^}]*\\}", "");
	}

	private static String parseIfThenElse(String str) {
		List<Span> spans = findCurlyBracketSpans(str);
		List<IfThenElse> ifThenElses = linkIfThenElses(str, spans);

		String result = new String(str); // Explicit copy
		for (IfThenElse ifThenElse : ifThenElses) {
			if (ifThenElse.condition.valid) {
				if (evaluateCondition(result.substring(ifThenElse.condition.start + 1, ifThenElse.condition.end - 1)))
					result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), ifThenElse.ifTrue.start + 1, ifThenElse.ifTrue.end - 2);
				else {
					if (ifThenElse.hasIfFalse)
						result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), ifThenElse.ifFalse.start + 1, ifThenElse.ifFalse.end - 2);
					else
						result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), 0, -1);
				}
			}
		}
		return result;
	}

    	private static String parseForEach(String str,  Map<String, String> parameterToValue) {
   		ForEach forEach = findNextForEach(str);

   		String result = new String(str); // Explicit copy
//	    	int count = 0;
	    	if (forEach != null) {
//		    System.err.println("foreach #" + count);

		    ForEachCollection forEachCollection = expandForEachLists(result, forEach, parameterToValue);
		    StringBuilder sb = new StringBuilder();

		    String statement = result.substring(forEach.statement.start + 1, forEach.statement.end - 1);

		    for (int i = 0; i < forEachCollection.count; ++i) {
		        String iterationStatement = new String(statement); // Explicit copy
		        for (int j = 0; j < forEachCollection.tokens.size(); ++j) {
			  String oldToken = "@" + forEachCollection.tokens.get(j);
			  String newToken = oldToken + "_" + i;
			  iterationStatement = iterationStatement.replaceAll(oldToken, newToken);
		        }

		        sb.append(iterationStatement).append("\n");
		    }

//		    System.err.println("BEFORE: " + statement);

		    result = StringUtils.replace(result, forEach.collection.start, forEach.statement.end, sb.toString());

//		    System.err.println("AFTER: " + result);

//		    System.err.println("SB:\n" + sb.toString());

		    //str = replace();

		    for (Map.Entry<String, String> entry : forEachCollection.parameterMap.entrySet()) {
		        parameterToValue.put(entry.getKey(), entry.getValue());
		    }

		    for (String oldParameter : forEachCollection.tokens) {
		        parameterToValue.remove(oldParameter);
		    }


//		    forEach.statement.start;
//		    ++count;
		}
//   		for (IfThenElse ifThenElse : ifThenElses) {
//   			if (ifThenElse.condition.valid) {
//   				if (evaluateCondition(result.substring(ifThenElse.condition.start + 1, ifThenElse.condition.end - 1)))
//   					result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), ifThenElse.ifTrue.start + 1, ifThenElse.ifTrue.end - 2);
//   				else {
//   					if (ifThenElse.hasIfFalse)
//   						result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), ifThenElse.ifFalse.start + 1, ifThenElse.ifFalse.end - 2);
//   					else
//   						result = replace(result, spans, ifThenElse.start(), ifThenElse.end(), 0, -1);
//   				}
//   			}
//   		}
   		return result;
   	}

	private static String renderSql(String str, Map<String, String> parameterToValue) {
	    	String result = parseParameterDefaults(str, parameterToValue);
	    	result = parseForEach(result, parameterToValue);
		result = substituteParameters(result, parameterToValue);
		result = parseIfThenElse(result);
		return result;
	}

	/**
	 * This function takes parameterized SQL and a list of parameter values and renders the SQL that can be send to the server. Parameterization syntax:<br/>
	 * &#64;parameterName<br/>
	 * Parameters are indicated using a &#64; prefix, and are replaced with the actual values provided in the renderSql call.<br/>
	 * <br/>
	 * {DEFAULT &#64;parameterName = parameterValue}<br/>
	 * Default values for parameters can be defined using curly and the DEFAULT keyword.<br/>
	 * <br/>
	 * {if}?{then}:{else}<br/>
	 * The if-then-else pattern is used to turn on or off blocks of SQL code.
	 * 
	 * @param sql
	 *            The parameterized SQL
	 * @param parameters
	 *            The names of the parameters (without the &#64;-sign).
	 * @param values
	 *            The values of the parameters.
	 * @return The rendered sql
	 */
	public static String renderSql(String sql, String[] parameters, String[] values) {
		Map<String, String> parameterToValue = new HashMap<String, String>();
		if (parameters != null)
			for (int i = 0; i < parameters.length; i++) {
				parameterToValue.put(parameters[i], values[i]);
			}
		return renderSql(sql, parameterToValue);
	}

}
