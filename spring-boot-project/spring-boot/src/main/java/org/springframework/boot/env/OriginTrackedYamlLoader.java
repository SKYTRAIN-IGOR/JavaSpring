/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import org.springframework.beans.factory.config.YamlProcessor;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.boot.origin.TextResourceOrigin.Location;
import org.springframework.core.io.Resource;

/**
 * Class to load {@code .yml} files into a map of {@code String} to
 * {@link OriginTrackedValue}.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 */
class OriginTrackedYamlLoader extends YamlProcessor {

	private final Resource resource;

	OriginTrackedYamlLoader(Resource resource) {
		this.resource = resource;
		setResources(resource);
	}

	@Override
	protected Yaml createYaml() {
		LoaderOptions loaderOptions = new LoaderOptions();
		loaderOptions.setAllowDuplicateKeys(false);
		loaderOptions.setMaxAliasesForCollections(Integer.MAX_VALUE);
		loaderOptions.setAllowRecursiveKeys(true);
		return createYaml(loaderOptions);
	}

	private Yaml createYaml(LoaderOptions loaderOptions) {
		BaseConstructor constructor = new OriginTrackingConstructor(loaderOptions);
		Representer representer = new Representer();
		DumperOptions dumperOptions = new DumperOptions();
		NoTimestampResolver resolver = new NoTimestampResolver();
		return new Yaml(constructor, representer, dumperOptions, loaderOptions, resolver);
	}

	List<Map<String, Object>> load() {
		final List<Map<String, Object>> result = new ArrayList<>();
		process((properties, map) -> result.add(getFlattenedMap(map)));
		return result;
	}

	/**
	 * {@link Constructor} that tracks property origins.
	 */
	private class OriginTrackingConstructor extends SafeConstructor {

		OriginTrackingConstructor(LoaderOptions loadingConfig) {
			super(loadingConfig);
		}

		@Override
		public Object getData() throws NoSuchElementException {
			Object data = super.getData();
			if (data instanceof CharSequence charSequence && charSequence.isEmpty()) {
				return null;
			}
			return data;
		}

		@Override
		protected Object constructObject(Node node) {
			if (node instanceof CollectionNode && ((CollectionNode<?>) node).getValue().isEmpty()) {
				return constructTrackedObject(node, super.constructObject(node));
			}
			if (node instanceof ScalarNode) {
				if (!(node instanceof KeyScalarNode)) {
					return constructTrackedObject(node, super.constructObject(node));
				}
			}
			if (node instanceof MappingNode mappingNode) {
				replaceMappingNodeKeys(mappingNode);
			}
			return super.constructObject(node);
		}

		private void replaceMappingNodeKeys(MappingNode node) {
			node.setValue(node.getValue().stream().map(KeyScalarNode::get).collect(Collectors.toList()));
		}

		private Object constructTrackedObject(Node node, Object value) {
			Origin origin = getOrigin(node);
			return OriginTrackedValue.of(getValue(value), origin);
		}

		private Object getValue(Object value) {
			return (value != null) ? value : "";
		}

		private Origin getOrigin(Node node) {
			Mark mark = node.getStartMark();
			Location location = new Location(mark.getLine(), mark.getColumn());
			return new TextResourceOrigin(OriginTrackedYamlLoader.this.resource, location);
		}

	}

	/**
	 * {@link ScalarNode} that replaces the key node in a {@link NodeTuple}.
	 */
	private static class KeyScalarNode extends ScalarNode {

		KeyScalarNode(ScalarNode node) {
			super(node.getTag(), node.getValue(), node.getStartMark(), node.getEndMark(), node.getScalarStyle());
		}

		static NodeTuple get(NodeTuple nodeTuple) {
			Node keyNode = nodeTuple.getKeyNode();
			Node valueNode = nodeTuple.getValueNode();
			return new NodeTuple(KeyScalarNode.get(keyNode), valueNode);
		}

		private static Node get(Node node) {
			if (node instanceof ScalarNode scalarNode) {
				return new KeyScalarNode(scalarNode);
			}
			return node;
		}

	}

	/**
	 * {@link Resolver} that limits {@link Tag#TIMESTAMP} tags, keeps only true and false
	 * as booleans, drops octal integers, base 2 integers, NaN numbers.
	 */
	private static class NoTimestampResolver extends Resolver {

		public static final Pattern SPRING_BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

		public static final Pattern SPRING_FLOAT = Pattern
				.compile("^(" + "[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+]?[0-9]+)?" + // (base
																						// 10)
						"|[-+]?(?:[0-9][0-9_]*)(?:[eE][-+]?[0-9]+)" + // (base 10,
																		// scientific
																		// notation
																		// without .)
						"|[-+]?\\.[0-9_]+(?:[eE][-+]?[0-9]+)?" + // (base 10, starting
																	// with .)
						"|[-+]?\\.(?:inf|Inf|INF)" + ")$");

		public static final Pattern SPRING_INT = Pattern.compile("^(?:" + "|[-+]?(?:0|[1-9][0-9_]*)" + // (base
																										// 10)
				"|[-+]?0x_*[0-9a-fA-F][0-9a-fA-F_]*" + // (base 16)
				")$");

		@Override
		protected void addImplicitResolvers() {
			addImplicitResolver(Tag.BOOL, SPRING_BOOL, "tfTF", 10);
			// INT must be before FLOAT because the regular expression for FLOAT matches
			// INT
			addImplicitResolver(Tag.INT, SPRING_INT, "-+0123456789", 120);
			addImplicitResolver(Tag.FLOAT, SPRING_FLOAT, "-+0123456789.", 120);
			addImplicitResolver(Tag.MERGE, MERGE, "<", 10);
			addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
			addImplicitResolver(Tag.NULL, EMPTY, null, 10);
		}

	}

}
