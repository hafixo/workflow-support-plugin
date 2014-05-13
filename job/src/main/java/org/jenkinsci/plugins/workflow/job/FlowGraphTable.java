package org.jenkinsci.plugins.workflow.job;

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Data model behind the tree list view of a flow graph
 *
 * @author Kohsuke Kawaguchi
 */
public class FlowGraphTable {
    private final FlowExecution execution;
    private List<Row> rows;

    public FlowGraphTable(FlowExecution execution) {
        this.execution = execution;
    }

    public List<Row> getRows() {
        return rows;
    }

    /**
     * Builds the tabular view of a flow node graph.
     */
    public void build() {
        Map<FlowNode, Row> rows = createAllRows();
        Row firstRow = buildForwardReferences(rows);
        buildTreeFromGraph(rows);
        buildTreeDepth(firstRow);
        this.rows = Collections.unmodifiableList(order(firstRow));
    }

    /**
     * Creates a {@link Row} for each reachable {@link FlowNode}
     */
    private Map<FlowNode, Row> createAllRows() {
        // queue of nodes to visit.
        // it's a stack and not queue to visit nodes in DFS
        Stack<FlowNode> q = new Stack<FlowNode>();
        q.addAll(execution.getCurrentHeads());

        // nodes that we've visited
        Map<FlowNode,Row> rows = new LinkedHashMap<FlowNode, Row>();

        while (!q.isEmpty()) {
            FlowNode n = q.pop();
            if (rows.containsKey(n)) continue;

            Row row = new Row(n);
            rows.put(n, row);

            q.addAll(n.getParents());
        }
        return rows;
    }

    /**
     * Builds up forward graph edge references from {@link FlowNode#getParents()} back pointers.
     */
    private Row buildForwardReferences(Map<FlowNode, Row> rows) {
        // build up all the forward references
        Row firstRow = null;
        for (Row r : rows.values()) {
            FlowNode n = r.node;
            for (FlowNode p : n.getParents()) {
                rows.get(p).addGraphChild(r);
            }
            if (n.getParents().isEmpty()) {
                if (firstRow==null)
                    firstRow = r;
                else {
                    // in an unlikely case when we find multiple head nodes,
                    // treat them all as siblings
                    firstRow.addGraphSibling(r);
                }
            }

            if (r.isEnd()) {
                FlowEndNode en = (FlowEndNode) r.node;
                Row sr = rows.get(en.getStartNode());

                assert sr.endNode==null : "start/end mapping should be 1:1";
                sr.endNode = en;
            }
        }
        // graph shouldn't contain any cycle, so there should be at least one 'head node'
        assert firstRow!=null;
        return firstRow;
    }

    private void buildTreeFromGraph(Map<FlowNode, Row> rows) {
    /*
        Convert DAG into Tree

        In DAG, parent/child relationship is a successor relationship. For example,
        if an AtomNode A runs then AtomNode B runs, A is a parent of B.

        In the tree view, we'd like A to be the elder sibling of B. This is where
        we do that translation.

        The general strategy is that
        FlowStartNode has its graph children turned into tree children
        (for example so that a fork start node can have all its branches as tree children.)

        FlowEndNode gets dropped from the tree (and logically thought of as a part of the start node),
        but graph children of FlowEndNode become tree siblings of FlowStartNode.
        (TODO: what if the end node wants to show information, such as in the case of validated merge?)
        addTreeSibling/addTreeChild handles the logic of dropping end node from the tree.

        Other nodes (I'm thinking atom nodes are the only kinds here) have their graph children
        turned into tree siblings.
     */
        for (Row r : rows.values()) {
            if (r.isStart()) {
                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    r.addTreeChild(c);
                }
            } else
            if (r.isEnd()) {
                FlowEndNode en = (FlowEndNode) r.node;
                Row sr = rows.get(en.getStartNode());

                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    sr.addTreeSibling(c);
                }
            } else {
                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    r.addTreeSibling(c);
                }
            }
        }
    }

    /**
     * Sets {@link Row#treeDepth} to the depth of the node from its tree root.
     */
    private void buildTreeDepth(Row r) {
        r.treeDepth = 0;

        Stack<Row> q = new Stack<Row>();
        q.add(r);

        while (!q.isEmpty()) {
            r = q.pop();
            if (r.firstTreeChild!=null) {
                q.add(r.firstTreeChild);
                r.firstTreeChild.treeDepth = r.treeDepth +1;
            }
            if (r.nextTreeSibling!=null) {
                q.add(r.nextTreeSibling);
                r.nextTreeSibling.treeDepth = r.treeDepth;
            }
        }
    }

    /**
     * Order tree into a sequence.
     */
    private List<Row> order(Row r) {
        List<Row> rows = new ArrayList<Row>();

        Stack<Row> ancestors = new Stack<Row>();

        while (r!=null) {
            rows.add(r);

            if (r.firstTreeChild!=null) {
                if (r.nextTreeSibling!=null)
                    ancestors.push(r.nextTreeSibling);
                r = r.firstTreeChild;
            } else
            if (r.nextTreeSibling!=null) {
                r = r.nextTreeSibling;
            } else {
                r = ancestors.pop();
            }
        }

        return rows;
    }

    public class Row {
        private final FlowNode node;

        /**
         * We collapse {@link FlowStartNode} and {@link FlowEndNode} into one row.
         * When it happens, this field refers to {@link FlowEndNode} while
         * {@link #node} refers to {@link FlowStartNode}.
         */
        private FlowEndNode endNode;

        // reverse edges of node.parents, which forms DAG
        private Row firstGraphChild;
        private Row nextGraphSibling;

        // tree view
        private Row firstTreeChild;
        private Row nextTreeSibling;

        private int treeDepth = -1;

        public Row(FlowNode node) {
            this.node = node;
        }

        boolean isStart() {
            return node instanceof FlowStartNode;
        }

        boolean isEnd() {
            return node instanceof FlowEndNode;
        }

        void addGraphChild(Row r) {
            if (firstGraphChild ==null)
                firstGraphChild = r;
            else {
                firstGraphChild.addGraphSibling(r);
            }
        }

        void addGraphSibling(Row r) {
            Row s = this;
            while (s.nextGraphSibling !=null)
                s = s.nextGraphSibling;
            s.nextGraphSibling = r;
        }

        void addTreeChild(Row r) {
            if (r.isEnd())  return;

            if (firstTreeChild ==null)
                firstTreeChild = r;
            else {
                firstTreeChild.addTreeSibling(r);
            }
        }

        void addTreeSibling(Row r) {
            if (r.isEnd())  return;

            Row s = this;
            while (s.nextTreeSibling !=null)
                s = s.nextTreeSibling;
            s.nextTreeSibling = r;
        }
    }
    

}
