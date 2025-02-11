package de.kennyhml.e4.abap_highlighter;

import de.kennyhml.e4.abap_highlighter.context.ContextFlag;
import de.kennyhml.e4.abap_highlighter.AbapToken.TokenType;

import java.util.Map;
import java.util.Set;

import static de.kennyhml.e4.abap_highlighter.AbapToken.TokenType.*;

import java.util.*;

import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.IWordDetector;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

public class AbapKeywordRule extends BaseAbapRule {

	private static class AbapKeywordDetector implements IWordDetector {

		@Override
		public boolean isWordStart(char c) {
			return Character.isLetter(c);
		}

		@Override
		public boolean isWordPart(char c) {
			return !fKeywordTerminators.contains((char) c);
		}

		private static final Set<Character> fKeywordTerminators = Set.of(' ', '\r', '\n', '.', '(', '>', '!');
	}

	/**
	 * @brief Defines a possible completion for a keyword
	 */
	private static class KeywordCompletion {
		public KeywordCompletion(String relation, Set<TokenType> upcomingTokenType) {
			if (relation == null) {
				this.text = null;
			} else {
				this.text = Arrays.asList(relation.split(" "));
			}
			this.upcomingTokenType = upcomingTokenType;
		}

		public KeywordCompletion(String relation, TokenType upcomingTokenType) {

			this(relation, Set.of(upcomingTokenType));
		}

		public KeywordCompletion(String relation) {
			this(relation, Set.of());
		}

		public List<String> text;
		public Set<TokenType> upcomingTokenType;
	}

	@Override
	public boolean isPossibleInContext(AbapContext ctx) {
		// referencing a class or instance member, impossible to be a keyword.
		if (ctx.lastTokenMatchesAny(TokenType.OPERATOR, Set.of("=>", "->"))) {
			return false;
		}

		return true;
	}

	@Override
	public TokenType getTokenType() {
		return fToken.getType();
	}

	@Override
	public IToken evaluate(AbapScanner scanner) {
		AbapContext ctx = scanner.getContext();

		int c = scanner.peek();
		if (c == AbapScanner.EOF || !fDetector.isWordStart((char) c)) {
			return Token.UNDEFINED;
		}

		String text = scanner.readNext(fDetector);
		if (text == null || !isKeywordPossibleInContext(ctx, text)) {
			return Token.UNDEFINED;
		}
		
		// Special case for handling 'me' keyword as the dash is part of alot of
		// other keywords and cannot simply be filtered out.
		if (text.equals("me-")) {
			scanner.unread();
			text = text.replace("-", "");
		}

		if (!fAllKeywords.contains(text) && !fAllKeywords.contains(text.replace(":", ""))) {
			return Token.UNDEFINED;
		}

		final KeywordCompletion completed = resolveRelatedKeywords(scanner, text);
		if (completed != null) {
			if (completed.text != null) {
				fToken.setText(text + " " + String.join(" ", completed.text));
			} else {
				fToken.setText(text);
			}
			ctx.setNextPossibleTokens(completed.upcomingTokenType);
		} else {
			return Token.UNDEFINED;
//			fToken.setText(text);
//			ctx.addToken(fToken);
//			ctx.clearNextPossibleTokens();
			
		}

		ctx.addToken(fToken);
		checkContextChanged(ctx);
		// Set next possible token type based on the keyword (if known)
		return fToken;
	}

	
	private boolean isKeywordPossibleInContext(AbapContext ctx, String text) {
		// Only keyword that can appear during a struct declaration is the "end of ..." keyword.
		// This will prevent fields of a structure being identified as keywords.
		if (ctx.active(ContextFlag.STRUCT_DECL) && ctx.lastTokenMatches(TokenType.DELIMITER) && !text.equals("end")) {
			return false;
		}
		
		return true;
	}
	
	private void checkContextChanged(AbapContext ctx) {

		String lastWord = ctx.getLastToken().getText();
		String withoutMod = lastWord.replaceAll(":", "");

		// Check for types: begin of ... end of.
		if (ctx.hasWord("types:")) {
			if (lastWord.equals("begin of")) {
				ctx.activate(ContextFlag.STRUCT_DECL);
			} else if (lastWord.equals("end of")) {
				ctx.deactivate(ContextFlag.STRUCT_DECL);
			}
		} else if (fDataContextActivators.contains(lastWord)) {
			ctx.activate(ContextFlag.DATA_DECL);
		} else if (fFuncContextActivators.contains(lastWord)) {
			ctx.activate(ContextFlag.FN_DECL);
		}
		// Also check if the last word with the ':' removed fits.
		else if (fDataContextActivators.contains(withoutMod)) {
			ctx.activate(ContextFlag.DATA_MULTI_DECL);
		} else if (fFuncContextActivators.contains(withoutMod)) {
			ctx.activate(ContextFlag.FN_MULTI_DECL);
		}
	}

	/**
	 * @brief Checks whether the upcoming tokens after the scanned keyword belong to
	 *        the same expression. For example, after scanning the keyword "ref", it
	 *        is possible for it to group with "ref to".
	 */
	private KeywordCompletion resolveRelatedKeywords(AbapScanner scanner, String text) {

		String ret = text;
		
		List<KeywordCompletion> completions = null;
		
		for (Map.Entry<Set<String>, List<KeywordCompletion>> entry : fKeywordCompletions.entrySet()) {
			if (entry.getKey().contains(text)) {
				completions = entry.getValue();
				break;
			}
		}
		
		// No relations defined for this keyword.
		if (completions == null) {
			return null;
		}
		
		int maxWords = 0;
		int wordCount = 0;
		for (KeywordCompletion comp : completions) {
			if (comp.text != null && comp.text.size() > maxWords) {
				maxWords = comp.text.size();
			}
		}

		// there is no completion for this, only types are provided.
		if (maxWords == 0) {
			return completions.get(0);
		}
		
		// Keep track of each completion whether it has matched up so far
		List<Boolean> tracking = new ArrayList<>(Collections.nCopies(completions.size(), true));
		List<Integer> matched = new ArrayList<>(Collections.nCopies(completions.size(), 0));
		
		while (wordCount < maxWords && tracking.contains(true)) {
			// Skip whitespaces
			while (Character.isWhitespace(scanner.peek())) {
				scanner.read();
				ret += " ";
			}
			
			// check the different continuations
			String nextWord = scanner.peekNext(fDetector);
			boolean anyMatched = false;
			
			// Check each completion we are still tracking whether its next word
			// matches with the word we scanned. If it does remember how many times
			// that completion matched. The completion that matched fully and the longest
			// will be chosen in the end.
			for (int i = 0; i < completions.size(); i++) {
				if (!tracking.get(i) || completions.get(i).text == null) {continue;}
				
				// Get the word this completion would next expect
				String expecting = "";
				try {
					expecting = completions.get(i).text.get(wordCount);
				} catch (IndexOutOfBoundsException e) {
				}
				
				// Stop tracking if there is no next word for this completion or it didnt match
				if (expecting.isEmpty() || !expecting.equals(nextWord)) {
					tracking.set(i, false); // stop tracking the completion
					continue;
				}
				
				anyMatched = true;
				matched.set(i, wordCount + 1);
			}
			if (!anyMatched) {
				break;
			}
			scanner.readNext(fDetector);
			ret += nextWord;
			wordCount++;
		};
		
		Integer longest = 0;
		KeywordCompletion match = null;
		for (int i = 0; i < completions.size(); i++) {
			int matchedWords = matched.get(i);
			
			// words matched and full completion dont match up, cant be this one
			
			if (matchedWords == 0) {
				if (completions.get(i).text != null) {
					continue;
				}
			} else if(matchedWords != completions.get(i).text.size()) {
				continue;
			}
			
			if (matchedWords > longest || match == null) {
				match = completions.get(i);
				longest = matchedWords;
			}
		}
		return match;
	}

	private static final Set<String> fDataContextActivators = Set.of("data", "class-data", "parameters");
	private static final Set<String> fFuncContextActivators = Set.of("methods", "class-methods");

	private static final Set<String> fAllKeywords = Set.of("if", "else", "elseif", "endif", "class", "endclass", "method",
			"endmethod", "methods", "type", "types", "implementation", "definition", "data", "table", "of", "public",
			"private", "protected", "section", "begin", "end", "final", "create", "is", "not", "initial", "and", "or",
			"importing", "exporting", "changing", "raising", "receiving", "line", "range", "loop", "at", "endloop",
			"endwhile", "append", "appending", "fields", "to", "from", "select", "into", "for", "all", "entries", "in",
			"where", "single", "value", "standard", "ref", "when", "write", "inheriting", "returning", "class-methods",
			"class-data", "case", "others", "abstract", "assigning", "field-symbol", "new", "try", "catch", "endtry",
			"join", "inner", "outer", "left", " right", "like", "update", "set", "delete", "modify", "no-gaps",
			"condense", "concatenate", "on", "as", "raise", "exception", "constants", "optional", "default", "call",
			"with", "non-unique", "unique", "key", "occurrences", "replace", "then", "switch", "continue", "message",
			"corresponding", "sort", "by", "duplicates", "return", "function", "conv", "exceptions", "reference",
			"preferred", "parameter", "length", "decimals", "empty", "components", "sorted", "hashed", "separated",
			"character", "mode", "respecting", "blanks", "byte", "include", "initialization", "start-of-selection",
			"report", "selection-screen", "parameters", "lower", "obligatory", "select-options", "block", "frame",
			"title", "intervals", "no", "starting", "visible", "checkbox", "user-command", "radiobutton", "group",
			"listbox", "modif", "id", "screen", "split", "cond", "reduce", "init", "next", "move-corresponding",
			"supplied", "insert", "authority-check", "object", "field", "clear", "do", "enddo", "eq", "ne", "lt", "gt",
			"le", "ge", "co", "cn", "ca", "na", "cs", "ns", "cp", "np", "me", "endcase", "assign", "field-symbols",
			"base", "check", "get", "time", "stamp", "commit", "work", "search", "assigned", "exit", "move", "read",
			"transporting", "convert", "date", "zone", "times", "bypassing", "buffer", "component", "distinct",
			"requested", "testing", "duration", "short", "risk", "level", "harmless", "local", "global", "friends",
			"interfaces", "renaming", "suffix", "structure", "resumable", "read-only", "interface", "endinterface");

	private static Map<Set<String>, List<KeywordCompletion>> fKeywordCompletions = Map.ofEntries(
			
			// could be followed by pretty much anything..
			Map.entry(Set.of("if", "and", "or"),
					List.of(new KeywordCompletion(null))),
			
			// keywords that always complete a statement and are always followed by a dot.
			Map.entry(Set.of("endif", "endclass", "endinterface", "endmethod", "implementation", "endloop"),
					List.of(new KeywordCompletion(null, DELIMITER))),
			
			Map.entry(Set.of("is"),
					List.of(new KeywordCompletion("initial", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion("bound", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion(null, Set.of(KEYWORD, DELIMITER))
							)),
			
			Map.entry(Set.of("not"),
					List.of(new KeywordCompletion("initial", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion("bound", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion(null, Set.of(KEYWORD))	
							)),
			
			Map.entry(Set.of("public", "protected", "private"),
					List.of(new KeywordCompletion("section", Set.of(DELIMITER)),
							new KeywordCompletion(null, Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion("not", Set.of(KEYWORD))
							)),
			
			Map.entry(Set.of("create"),
					List.of(new KeywordCompletion("public", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion("protected", Set.of(KEYWORD, DELIMITER)),
							new KeywordCompletion("private", Set.of(KEYWORD, DELIMITER))
							)),
			
			
			Map.entry(Set.of("definition", "abstract", "final"),
					List.of(new KeywordCompletion(null, Set.of(KEYWORD, DELIMITER)))),
			
			
			Map.entry(Set.of("inheriting"),
					List.of(new KeywordCompletion("from", Set.of(TYPE_IDENTIFIER)))),
			
			// type ref to..., ref itself is also fully qualified to get an object
			// reference, e.g ref #( obj ).
			Map.entry(Set.of("ref"), 
					List.of(new KeywordCompletion("to", TYPE_IDENTIFIER))),

			// Table comprehension, "for line in ..."
			// "For all entries in.."
			Map.entry(Set.of("for"), 
					List.of(new KeywordCompletion(null, IDENTIFIER),
							new KeywordCompletion("all entries in", IDENTIFIER),
							new KeywordCompletion("testing", Set.of(KEYWORD, DELIMITER)))
					),
			
			
			Map.entry(Set.of("raise"), 
					List.of(new KeywordCompletion("exception type", TYPE_IDENTIFIER),
							new KeywordCompletion("exception", IDENTIFIER)
					)),
			
			
			// Could be any token that yields a value, e.g access to static class attribute, 
			// function return value of plain old variable
			Map.entry(Set.of("append"), 
					List.of(new KeywordCompletion(null, Set.of(IDENTIFIER, KEYWORD, TYPE_IDENTIFIER)),
							new KeywordCompletion("initial line to", Set.of(IDENTIFIER, KEYWORD, TYPE_IDENTIFIER)),
							new KeywordCompletion("lines of", Set.of(IDENTIFIER, KEYWORD, TYPE_IDENTIFIER)),
							new KeywordCompletion("corresponding", Set.of(IDENTIFIER, KEYWORD, TYPE_IDENTIFIER))
					)),
			
			
			Map.entry(Set.of("to"), 
					List.of(new KeywordCompletion(null, Set.of(TYPE_IDENTIFIER, IDENTIFIER)))),
			
			
			Map.entry(Set.of("class", "interface"), 
					List.of(new KeywordCompletion(null, TYPE_IDENTIFIER))),
			
			// raising resumable(exception), optional, raising itself is fully qualified
			Map.entry(Set.of("raising"),
					List.of(new KeywordCompletion("resumable", DELIMITER),
							new KeywordCompletion(null, TYPE_IDENTIFIER))
					),

			// could be "type ref to", but also "types: begin of.."
			Map.entry(Set.of("type", "types:"),
					List.of(new KeywordCompletion(null, Set.of(TYPE_IDENTIFIER, KEYWORD)))),

			Map.entry(Set.of("types"),
					List.of(new KeywordCompletion(null, Set.of(TYPE_IDENTIFIER)))),
			
			
			Map.entry(Set.of("data", "data:", "constants", "constants:"),
					List.of(new KeywordCompletion(null, Set.of(IDENTIFIER, KEYWORD)))),
			
			// "begin of mytype" ... "end of mytype"..
			Map.entry(Set.of("begin", "end", "range", "line"), 
					List.of(new KeywordCompletion("of", Set.of(TYPE_IDENTIFIER)),
							new KeywordCompletion("of block", Set.of(IDENTIFIER))
					)),
			
			
 			Map.entry(Set.of("loop"), 
					List.of(new KeywordCompletion("at", Set.of(FUNCTION, TYPE_IDENTIFIER, IDENTIFIER)))),
			
			// Could be "value some_type( )" but also "data x type i value 123", so type or literal
 			Map.entry(Set.of("value"), 
					List.of(new KeywordCompletion(null, Set.of(TYPE_IDENTIFIER, STRING, LITERAL, IDENTIFIER, DELIMITER)))),
			
			
			// All of these keywords guarantee a type identifier following them
			Map.entry(Set.of("new", "catch", "conv", "cond", "corresponding", "reduce"), 
					List.of(new KeywordCompletion(null, Set.of(TYPE_IDENTIFIER)))),
			
			Map.entry(Set.of("preferred"), 
					List.of(new KeywordCompletion("parameter", IDENTIFIER))),
			
			Map.entry(Set.of("parameters", "parameters:"), 
					List.of(new KeywordCompletion(null, IDENTIFIER))),
			
			Map.entry(Set.of("method", "methods", "methods:"), 
					List.of(new KeywordCompletion(null, FUNCTION))),

			
			Map.entry(Set.of("importing", "exporting", "returning", "changing"),
					List.of(new KeywordCompletion(null, Set.of(IDENTIFIER, OPERATOR, KEYWORD)))
					),
			
			Map.entry(Set.of("reference"),
					List.of(new KeywordCompletion(null, Set.of(KEYWORD, DELIMITER)))
					),
			// type table of..
			Map.entry(Set.of("table"), 
					List.of(new KeywordCompletion("of", TokenType.TYPE_IDENTIFIER)))
			
			);

	private static Color KEYWORD_COLOR = new Color(86, 156, 214);

	private AbapToken fToken = new AbapToken(KEYWORD_COLOR, AbapToken.TokenType.KEYWORD);

	private AbapKeywordDetector fDetector = new AbapKeywordDetector();

}
