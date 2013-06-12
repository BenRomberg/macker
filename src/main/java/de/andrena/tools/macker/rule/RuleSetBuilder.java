/*______________________________________________________________________________
 *
 * Macker   http://innig.net/macker/
 *
 * Copyright 2002-2003 Paul Cantrell
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2, as published by the
 * Free Software Foundation. See the file LICENSE.html for more information.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY, including the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the license for more details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc. / 59 Temple
 * Place, Suite 330 / Boston, MA 02111-1307 / USA.
 *______________________________________________________________________________
 */

package de.andrena.tools.macker.rule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.jdom.Attribute;
import org.jdom.DocType;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.XMLOutputter;

import de.andrena.tools.macker.Macker;
import de.andrena.tools.macker.rule.filter.Filter;
import de.andrena.tools.macker.rule.filter.FilterFinder;
import de.andrena.tools.macker.util.io.NullOutputStream;

public class RuleSetBuilder {

	public static final URL MACKER_DTD = Thread.currentThread().getContextClassLoader()
			.getResource(Macker.PACKAGE_SLASHES + "/macker.dtd");

	private SAXBuilder saxBuilder, saxBuilderVerify;
	private XMLOutputter xmlOut;
	private String dtdUrlS;

	public RuleSetBuilder() {
		saxBuilder = new SAXBuilder(false);
		saxBuilder.setEntityResolver(new MackerEntityResolver());
		saxBuilderVerify = new SAXBuilder(true);
		xmlOut = new XMLOutputter();

		// ! hack to get around bogus messages generated by Ant's classloader
		PrintStream realErr = System.err;
		try {
			System.setErr(new PrintStream(new NullOutputStream()));
			dtdUrlS = MACKER_DTD.toExternalForm();
		} finally {
			System.setErr(realErr);
		}
	}

	public Collection<RuleSet> build(InputStream is) throws RulesException {
		try {
			return build(saxBuilder.build(is));
		} catch (JDOMException jdome) {
			throw new RulesDocumentException(jdome);
		} catch (IOException ioe) {
			throw new RulesDocumentException(ioe);
		}
	}

	public Collection<RuleSet> build(Reader reader) throws RulesException {
		try {
			return build(saxBuilder.build(reader));
		} catch (JDOMException jdome) {
			throw new RulesDocumentException(jdome);
		} catch (IOException ioe) {
			throw new RulesDocumentException(ioe);
		}
	}

	public Collection<RuleSet> build(File file) throws RulesException {
		try {
			return build(saxBuilder.build(file));
		} catch (JDOMException jdome) {
			throw new RulesDocumentException(jdome);
		} catch (IOException ioe) {
			throw new RulesDocumentException(ioe);
		}
	}

	public Collection<RuleSet> build(String fileName) throws RulesException {
		return build(new File(fileName));
	}

	public Collection<RuleSet> build(Document doc) throws RulesException {
		validateAgainstDTD(doc);
		return build(doc.getRootElement());
	}

	public Collection<RuleSet> build(Element elem) throws RulesException {
		Collection<RuleSet> ruleSets = new ArrayList<RuleSet>();

		for (Element rsElem : getChildren(elem))
			ruleSets.add(buildRuleSet(rsElem, RuleSet.getMackerDefaults()));
		return ruleSets;
	}

	@SuppressWarnings("unchecked")
	private Collection<Element> getChildren(Element elem) {
		return elem.getChildren("ruleset");
	}

	private void validateAgainstDTD(Document doc) throws RulesDocumentException {
		doc.setDocType(new DocType("macker", dtdUrlS));

		StringWriter out = new StringWriter();
		try {
			xmlOut.output(doc, out);
		} catch (IOException ioe) {
			throw new RuntimeException("Unexpected output exception.", ioe);
		}
		Reader in = new StringReader(out.toString());
		try {
			saxBuilderVerify.build(in);
		} catch (JDOMException jdome) {
			throw new RulesDocumentException(jdome);
		} catch (IOException ioe) {
			throw new RulesDocumentException(ioe);
		}
	}

	public RuleSet buildRuleSet(Element ruleSetElem, RuleSet parent) throws RulesException {
		RuleSet ruleSet = new RuleSet(parent);

		String name = ruleSetElem.getAttributeValue("name");
		if (name != null)
			ruleSet.setName(name);

		buildSeverity(ruleSet, ruleSetElem);

		for (Element subElem : getChildren(ruleSetElem)) {
			String subElemName = subElem.getName();
			if (subElemName.equals("pattern")) {
				String patternName = subElem.getAttributeValue("name");
				if (ruleSet.declaresPattern(patternName))
					throw new RulesDocumentException(subElem, "Pattern named \"" + patternName
							+ "\" is already defined in this context");

				ruleSet.setPattern(patternName, buildPattern(subElem, ruleSet));
			} else if (subElemName.equals("subset")) {
				if (ruleSet.getSubsetPattern() != null)
					throw new RulesDocumentException(subElem, "<ruleset> may only contain a single <subset> element");
				ruleSet.setSubsetPattern(buildPattern(subElem, ruleSet));
			} else if (subElemName.equals("access-rule"))
				ruleSet.addRule(buildAccessRule(subElem, ruleSet));
			else if (subElemName.equals("var"))
				ruleSet.addRule(buildVariable(subElem, ruleSet));
			else if (subElemName.equals("foreach"))
				ruleSet.addRule(buildForEach(subElem, ruleSet));
			else if (subElemName.equals("ruleset"))
				ruleSet.addRule(buildRuleSet(subElem, ruleSet));
			else if (subElemName.equals("message"))
				ruleSet.addRule(buildMessage(subElem, ruleSet));
		}

		return ruleSet;
	}

	public Pattern buildPattern(Element patternElem, RuleSet ruleSet) throws RulesException {
		return buildPattern(patternElem, ruleSet, true, null);
	}

	public Pattern buildPattern(Element patternElem, RuleSet ruleSet, boolean isTopElem, Pattern nextPat)
			throws RulesException {
		// handle options

		String otherPatName = patternElem.getAttributeValue("pattern");
		String className = getClassNameAttributeValue(patternElem);
		String filterName = patternElem.getAttributeValue("filter");

		CompositePatternType patType;
		if (patternElem.getName().equals("include"))
			patType = CompositePatternType.INCLUDE;
		else if (patternElem.getName().equals("exclude"))
			patType = (filterName == null) ? CompositePatternType.EXCLUDE : CompositePatternType.INCLUDE;
		else if (isTopElem)
			patType = CompositePatternType.INCLUDE;
		else
			throw new RulesDocumentException(patternElem, "Invalid element <" + patternElem.getName() + "> --"
					+ " expected <include> or <exclude>");

		if (otherPatName != null && className != null)
			throw new RulesDocumentException(patternElem,
					"patterns cannot have both a \"pattern\" and a \"class\" attribute");

		// do the head thing

		Pattern head = null;
		if (className != null)
			head = new RegexPattern(className);
		else if (otherPatName != null) {
			head = ruleSet.getPattern(otherPatName);
			if (head == null)
				throw new UndeclaredPatternException(otherPatName);
		}

		// build up children

		Pattern childrenPat = null;
		List<Element> children = new ArrayList<Element>(getChildren(patternElem)); // !
																					// workaround
																					// for
																					// bug
																					// in
																					// JUnit
		// List children = patternElem.getChildren(); // this should work
		// instead when JUnit bug is fixed
		for (ListIterator<Element> childIter = children.listIterator(children.size()); childIter.hasPrevious();) {
			Element subElem = childIter.previous();
			if (subElem.getName().equals("message"))
				continue;

			childrenPat = buildPattern(subElem, ruleSet, false, childrenPat);
		}

		// wrap head in a filter if necessary

		if (filterName != null) {
			Map<String, String> options = new HashMap<String, String>();
			for (Attribute attr : getAttributes(patternElem))
				options.put(attr.getName(), attr.getValue());
			options.remove("name");
			options.remove("pattern");
			options.remove("class");
			options.remove("regex");

			Filter filter = FilterFinder.findFilter(filterName);
			head = filter.createPattern(ruleSet,
					(head == null) ? new ArrayList<Pattern>() : Collections.singletonList(head), options);

			if (patternElem.getName().equals("exclude"))
				head = CompositePattern.create(CompositePatternType.EXCLUDE, head, null, null);
		}

		// pull together composite

		return CompositePattern.create(patType, head, childrenPat, nextPat);
	}

	@SuppressWarnings("unchecked")
	private Collection<Attribute> getAttributes(Element patternElem) {
		return patternElem.getAttributes();
	}

	public Variable buildVariable(Element forEachElem, RuleSet parent) throws RulesException {
		String varName = forEachElem.getAttributeValue("name");
		if (varName == null)
			throw new RulesDocumentException(forEachElem, "<var> is missing the \"name\" attribute");

		String value = forEachElem.getAttributeValue("value");
		if (value == null)
			throw new RulesDocumentException(forEachElem, "<var> is missing the \"value\" attribute");

		return new Variable(parent, varName, value);
	}

	public Message buildMessage(Element messageElem, RuleSet parent) throws RulesException {
		Message message = new Message(parent, messageElem.getText());
		buildSeverity(message, messageElem);
		return message;
	}

	public ForEach buildForEach(Element forEachElem, RuleSet parent) throws RulesException {
		String varName = forEachElem.getAttributeValue("var");
		if (varName == null)
			throw new RulesDocumentException(forEachElem, "<foreach> is missing the \"var\" attribute");

		String className = getClassNameAttributeValue(forEachElem);
		if (className == null)
			throw new RulesDocumentException(forEachElem, "<foreach> is missing the \"class\" attribute");

		ForEach forEach = new ForEach(parent);
		forEach.setVariableName(varName);
		forEach.setRegex(className);
		forEach.setRuleSet(buildRuleSet(forEachElem, parent));
		return forEach;
	}

	public AccessRule buildAccessRule(Element ruleElem, RuleSet ruleSet) throws RulesException {
		AccessRule prevRule = null, topRule = null;
		for (Element subElem : getChildren(ruleElem)) {
			AccessRule accRule = new AccessRule(ruleSet);

			if (subElem.getName().equals("allow"))
				accRule.setType(AccessRuleType.ALLOW);
			else if (subElem.getName().equals("deny"))
				accRule.setType(AccessRuleType.DENY);
			else if (subElem.getName().equals("from") || subElem.getName().equals("to")
					|| subElem.getName().equals("message"))
				continue;
			else
				throw new RulesDocumentException(subElem, "Invalid element <" + subElem.getName() + "> --"
						+ " expected an access rule (<deny> or <allow>)");

			Element fromElem = subElem.getChild("from");
			if (fromElem != null)
				accRule.setFrom(buildPattern(fromElem, ruleSet));

			Element toElem = subElem.getChild("to");
			if (toElem != null)
				accRule.setTo(buildPattern(toElem, ruleSet));

			if (!subElem.getChildren().isEmpty())
				accRule.setChild(buildAccessRule(subElem, ruleSet));

			if (topRule == null)
				topRule = accRule;
			else
				prevRule.setNext(accRule);
			prevRule = accRule;
		}
		if (topRule != null) {
			topRule.setMessage(ruleElem.getChildText("message"));
			buildSeverity(topRule, ruleElem);
		}
		return topRule;
	}

	public void buildSeverity(Rule rule, Element elem) throws RulesDocumentException {
		String severityS = elem.getAttributeValue("severity");
		if (severityS != null && !"".equals(severityS)) {
			RuleSeverity severity;
			try {
				severity = RuleSeverity.fromName(severityS);
			} catch (IllegalArgumentException iae) {
				throw new RulesDocumentException(elem, iae.getMessage());
			}
			rule.setSeverity(severity);
		}
	}

	private String getClassNameAttributeValue(Element elem) {
		String value = elem.getAttributeValue("class");
		if (value == null) {
			value = elem.getAttributeValue("regex");
			if (value != null)
				System.err
						.println("WARNING: The \"regex\" attribute is deprecated, and will be removed in v1.0.  Use \"class\" instead");
		}
		return value;
	}

}
