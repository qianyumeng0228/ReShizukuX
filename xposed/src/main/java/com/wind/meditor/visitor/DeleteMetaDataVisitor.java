package com.wind.meditor.visitor;

import com.wind.meditor.property.ModificationProperty;

import java.util.ArrayList;
import java.util.List;

import pxb.android.axml.NodeVisitor;

/**
 * @author Windysha
 */
public class DeleteMetaDataVisitor extends NodeVisitor {

    private NodeVisitor parentWriter;
    private List<ModificationProperty.MetaData> deleteMetaDataList;
    private String tagNs;
    private String tagName;

    private List<PendingAttr> pendingAttrs = new ArrayList<>();
    private boolean shouldDeleteNode = false;
    private boolean nameChecked = false;
    private int lineNumber = -1;

    DeleteMetaDataVisitor(NodeVisitor parentWriter, List<ModificationProperty.MetaData> deleteMetaDataList,
                          String ns, String name) {
        super(null);
        this.parentWriter = parentWriter;
        this.deleteMetaDataList = deleteMetaDataList;
        this.tagNs = ns;
        this.tagName = name;
    }

    @Override
    public void line(int ln) {
        lineNumber = ln;
    }

    @Override
    public void attr(String ns, String name, int resourceId, int type, Object obj) {
        if (!nameChecked) {
            if (!"name".equals(name)) {
                pendingAttrs.add(new PendingAttr(ns, name, resourceId, type, obj));
                return;
            }
            nameChecked = true;
            shouldDeleteNode = isDeleteMetaData(obj);
            if (shouldDeleteNode) {
                pendingAttrs.clear();
                return;
            }
            createWriterNode();
            flushPendingAttrs();
        }
        if (!shouldDeleteNode) {
            super.attr(ns, name, resourceId, type, obj);
        }
    }

    @Override
    public NodeVisitor child(String ns, String name) {
        checkNameAttr();
        if (shouldDeleteNode) {
            return null;
        }
        return super.child(ns, name);
    }

    @Override
    public void end() {
        checkNameAttr();
        if (!shouldDeleteNode) {
            super.end();
        }
    }

    private void checkNameAttr() {
        if (!nameChecked) {
            nameChecked = true;
            createWriterNode();
            flushPendingAttrs();
        }
    }

    private void createWriterNode() {
        if (nv == null) {
            nv = parentWriter.child(tagNs, tagName);
            if (nv != null && lineNumber >= 0) {
                nv.line(lineNumber);
            }
        }
    }

    private void flushPendingAttrs() {
        for (PendingAttr pendingAttr : pendingAttrs) {
            super.attr(pendingAttr.ns, pendingAttr.name, pendingAttr.resourceId,
                    pendingAttr.type, pendingAttr.obj);
        }
        pendingAttrs.clear();
    }

    private boolean isDeleteMetaData(Object obj) {
        for (ModificationProperty.MetaData data : deleteMetaDataList) {
            if (data.getName() != null && data.getName().equals(obj)) {
                return true;
            }
        }
        return false;
    }

    private static class PendingAttr {
        String ns;
        String name;
        int resourceId;
        int type;
        Object obj;

        PendingAttr(String ns, String name, int resourceId, int type, Object obj) {
            this.ns = ns;
            this.name = name;
            this.resourceId = resourceId;
            this.type = type;
            this.obj = obj;
        }
    }
}
