/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.blockly.model;

import android.database.Observable;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.google.blockly.android.control.BlocklyController;
import com.google.blockly.utils.BlockLoadingException;
import com.google.blockly.utils.BlocklyXmlHelper;
import com.google.blockly.utils.ColorUtils;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * Base class for a Blockly Block.
 */
public class Block extends Observable<Block.Observer> implements BlockContainer {
    private static final String TAG = "Block";

    // Observable attributes of possible concern to BlockViews.
    @Retention(SOURCE)
    @IntDef({
            UPDATE_INPUTS_FIELDS_CONNECTIONS, UPDATE_COLOR, UPDATE_COMMENT, UPDATE_IS_SHADOW,
            UPDATE_IS_DISABLED, UPDATE_IS_COLLAPSED, UPDATE_IS_EDITABLE, UPDATE_IS_DELETABLE,
            UPDATE_TOOLTIP, UPDATE_CONTEXT_MENU, UPDATE_INPUTS_INLINE
    })
    public @interface UpdateState {}
    public static final int UPDATE_INPUTS_FIELDS_CONNECTIONS = 0x01;
    public static final int UPDATE_COLOR = 0x02;  // TODO
    public static final int UPDATE_COMMENT = 0x04;
    public static final int UPDATE_IS_SHADOW = 0x08;
    public static final int UPDATE_IS_DISABLED = 0x10;
    public static final int UPDATE_IS_COLLAPSED = 0x20;
    public static final int UPDATE_IS_EDITABLE = 0x40;
    public static final int UPDATE_IS_DELETABLE = 0x80;
    public static final int UPDATE_TOOLTIP = 0x0100;  // TODO
    public static final int UPDATE_CONTEXT_MENU = 0x0200;  // TODO
    public static final int UPDATE_INPUTS_INLINE = 0x0400;

    public interface Observer {
        /**
         * Called when any of the following block elements have changed, possibly triggering a
         * change in the BlockView.
         * <ul>
         *     <li>Inputs</li>
         *     <li>Fields</li>
         *     <li>Mutator</li>
         *     <li>Comment</li>
         *     <li>Shadow state</li>
         *     <li>Disabled state</li>
         *     <li>Collapsed state</li>
         *     <li>Editable state</li>
         *     <li>Deletable state</li>
         * </ul>
         * @param block The block updated.
         * @param updateStateMask A bit mask of {@link UpdateState} bits for the updated parts.
         */
        void onBlockUpdated(Block block, @UpdateState int updateStateMask);
    }

    // These values are immutable once a block is created
    private final BlocklyController mController;
    private final BlockFactory mFactory;
    private final String mId;
    private final String mType;
    private boolean mIsShadow;

    // Set by BlockFactory.applyMutator(). May only be set once.
    /* package */ String mMutatorId = null;
    /* package */ Mutator mMutator = null;

    // These values can be changed after creating the block
    private int mColor = ColorUtils.DEFAULT_BLOCK_COLOR;
    private List<Input> mInputList = Collections.<Input>emptyList();
    private Connection mOutputConnection;
    private Connection mNextConnection;
    private Connection mPreviousConnection;
    private List<Connection> mConnectionList = Collections.<Connection>emptyList();
    private String mTooltip = null;
    private String mComment = null;
    private boolean mHasContextMenu = true;
    private boolean mDeletable = true;
    private boolean mMovable = true;
    private boolean mEditable = true;
    private boolean mCollapsed = false;
    private boolean mDisabled = false;
    private boolean mInputsInline = false;

    // Keep track of whether inputsInline has ever been changed.
    private boolean mInputsInlineModified = false;

    private BlockContainer mParentContainer = null;

    /** Position of the block in the workspace. Only serialized for the root block. */
    private WorkspacePoint mPosition;

    /**
     * @param factory The factory creating this block.
     * @param definition The definition this block instantiates.
     * @param id The globally unique identifier for this block.
     * @param isShadow Whether the block should be a shadow block (default input value block).
     * @throws BlockLoadingException When the {@link BlockDefinition} throws errors.
     */
    Block(@Nullable BlocklyController controller, @NonNull BlockFactory factory,
          @NonNull BlockDefinition definition, @NonNull String id, boolean isShadow)
            throws BlockLoadingException {
        if (controller == null || factory == null || definition == null || id == null) {
            throw new IllegalArgumentException(
                    "Tried to instantiate a block but controller, factory, definition, or id was "
                    + "null.");
        }
        mController = controller;
        mFactory = factory;
        mId = id;

        mType = definition.getTypeName();
        mColor = definition.getColor();

        reshape(definition.createInputList(factory),
                definition.createOutputConnection(),
                definition.createPreviousConnection(),
                definition.createNextConnection());
        if (isShadow && containsVariableField()) {
            throw new BlockLoadingException("Shadow blocks may not contain variable fields.");
        }

        mInputsInline = definition.isInputsInlineDefault();
        mInputsInlineModified = false;

        mPosition = new WorkspacePoint(0, 0);
        setShadow(isShadow);

        mMutatorId = definition.getMutatorId();
        if (mMutatorId != null) {
            factory.applyMutator(mMutatorId, this);
        }
        List<String> extensionNames = definition.getExtensionNames();
        for (String name : extensionNames) {
            factory.applyExtension(name, this);
        }
    }

    /**
     * @return The name of the block. Not for display.
     */
    public String getType() {
        return mType;
    }

    /**
     * @return The unique identifier of the block. Not for display.
     */
    @Override
    public String getId() {
        return mId;
    }

    @Override
    public boolean isRootContainer() {
        return false;
    }

    @Override
    public BlockContainer getParentContainer() {
        return mParentContainer;  // TODO & WARNING: This value is never set.
    }

    /**
     * @return The root {@link BlockContainer}, if this block is attached to one.
     */
    // TODO & WARNING: This value will always be null until mParentContainer is set appropriately.
    public BlockContainer getRootContainer() {
        BlockContainer container = mParentContainer;
        while (container != null && !container.isRootContainer()) {
            container = container.getParentContainer();
        }
        return container;
    }

    /**
     * Adds to <code>outList</code> all block ids for this block and all child blocks.
     * This will not include occluded shadow block ids.
     *
     * @param outList List of ids to add to.
     */
    public void addAllBlockIds(List<String> outList) {
        outList.add(getId());
        int inputCount = mInputList.size();
        for (int i = 0; i < inputCount; ++i) {
            Input input = mInputList.get(i);
            Block connectedBlock = input.getConnectedBlock();
            if (connectedBlock != null) {
                connectedBlock.addAllBlockIds(outList);
            }
        }
        if (mNextConnection != null) {
            Block next = mNextConnection.getTargetBlock();
            if (next != null) {
                next.addAllBlockIds(outList);
            }
        }
    }

    /**
     * @return The color this block should be drawn in.
     */
    public int getColor() {
        return mColor;
    }

    /**
     * @return Whether the user can edit field values.
     * @see #setEditable(boolean)
     */
    public boolean isEditable() {
        return mEditable;
    }

    /**
     * If a block is editable, the user can edit the values of the block's fields.  The editable
     * state does not affect whether child blocks can be connected or disconnected.
     *
     * Blocks are editable by default.
     *
     * Warning: FieldViews do not yet respect block disabled state (#303).
     *
     * @param editable
     */
    public void setEditable(boolean editable) {
        if (editable == mEditable) {
            return;
        }
        // TODO: Event support? No current spec for changes to "editable".
        mEditable = editable;
        fireUpdate(UPDATE_IS_EDITABLE);
    }

    /**
     * @return Whether the block can be moved.
     * @see #setMovable(boolean)
     */
    public boolean isMovable() {
        return mMovable;
    }

    /**
     * If a block is movable, users can drag it as a root block on the workspace, or disconnect it
     * from its parent block.
     *
     * Blocks are movable by default.
     *
     * @param movable
     */
    public void setMovable(boolean movable) {
        if (mMovable == movable) {
            return;
        }
        // TODO: Event support? No current spec for changes to "moveable".
        mMovable = movable;
    }

    /**
     * @return Whether the user can delete / trash this block.
     */
    public boolean isDeletable() {
        return mDeletable;
    }

    /**
     * Users can delete (i.e., drag to trash) blocks that are deletable.
     *
     * Blocks are deletable by default.
     *
     * Warning: The Android Dragger implementation does not respect deletable false state (#305).
     *
     * @param deletable
     */
    public void setDeletable(boolean deletable) {
        if (mDeletable == deletable) {
            return;
        }
        // TODO: Event support? No current spec for changes to "deletable".
        mDeletable = deletable;
        fireUpdate(UPDATE_IS_DELETABLE);
    }

    /**
     * @return Whether the block is disabled.  Does not check if parent or ancestor is disabled.
     * @see #setDisabled(boolean)
     */
    public boolean isDisabledBlock() {
        return mDisabled;
    }

    /**
     * @return Whether the block is disabled, possibly via a disabled parent or ancestor block.
     * @see #setDisabled(boolean)
     */
    public boolean isDisabled() {
        if (mDisabled) {
            return true;
        }
        Block ancestor = getParentBlock();
        while (ancestor != null) {
            if (ancestor.mDisabled) {
                return true;
            }
            ancestor = ancestor.getParentBlock();
        }
        return false;
    }

    /**
     * Disabling a block is effectively a way to comment out a block. The block is skipped during
     * code generation, including all connection value input and statement input children.  Next
     * statement children are not effected.
     *
     * By default, blocks are not disabled.
     *
     * Warning: BlockViews do not yet render disabled state. (#306).
     *
     * @param disabled
     */
    public void setDisabled(final boolean disabled) {
        if (mDisabled == disabled) {
            return;
        }
        mController.groupAndFireEvents(new Runnable() {
            @Override
            public void run() {
                mDisabled = disabled;

                // Add change event before notifying observers that might add their own events.
                mController.addPendingEvent(new BlocklyEvent.ChangeEvent(
                        BlocklyEvent.ChangeEvent.ELEMENT_DISABLED, Block.this, mDisabled));
                fireUpdate(UPDATE_IS_DISABLED); // Block.Observers
            }
        });
    }

    /**
     * @return Whether the block is collapsed.
     * @see #setCollapsed(boolean)
     */
    public boolean isCollapsed() {
        return mCollapsed;
    }

    /**
     * Users can collapse blocks, to render them in a condensed, text-only form.
     *
     * Warning: Not yet supported by Android BlockViews (#307).
     *
     * @param collapsed Whether the block should be collapsed.
     */
    public void setCollapsed(final boolean collapsed) {
        if (collapsed == mCollapsed) {
            return;
        }
        mController.groupAndFireEvents(new Runnable() {
            @Override
            public void run() {
                mCollapsed = collapsed;

                // Add change event before notifying observers that might add their own events.
                mController.addPendingEvent(new BlocklyEvent.ChangeEvent(
                        BlocklyEvent.ChangeEvent.ELEMENT_COLLAPSED, Block.this, mCollapsed));
                fireUpdate(UPDATE_IS_COLLAPSED);
            }
        });
    }

    /**
     * Gets the position of this block. The position is the top left for LTR configurations and the
     * top right for RTL configurations. This position is only meaningful for top level blocks. All
     * other blocks will have a position that is dependent on the block rendering relative to the
     * top level block.
     *
     * @return The coordinates of the start corner of this block in Workspace coordinates.
     */
    public WorkspacePoint getPosition() {
        return mPosition;
    }

    /**
     * @return A list of connections (including inputs, next, previous and outputs) on this block.
     */
    public List<Connection> getAllConnections() {
        return mConnectionList;
    }

    /**
     * Add all connections on the block to the given list.
     *
     * @param addTo The list to update.
     */
    public void getAllConnections(List<Connection> addTo) {
        addTo.addAll(mConnectionList);
    }

    /**
     * Add all connections on the block and its descendants to the given list.
     *
     * @param addTo The list to update.
     */
    public void getAllConnectionsRecursive(List<Connection> addTo) {
        getAllConnections(addTo);
        for (int i = 0; i < mConnectionList.size(); i++) {
            Connection conn = mConnectionList.get(i);
            int type = conn.getType();
            Block target = conn.getTargetBlock();
            if (type != Connection.CONNECTION_TYPE_OUTPUT
                    && type != Connection.CONNECTION_TYPE_PREVIOUS
                    && target != null) {
                target.getAllConnectionsRecursive(addTo);
            }
        }
    }

    /**
     * Set the position of this block in the workspace.
     *
     * @param x The workspace x position.
     * @param y The workspace y position.
     */
    public void setPosition(float x, float y) {
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)) {
            throw new IllegalArgumentException("Position must be a real, finite number.");
        }
        mPosition.x = x;
        mPosition.y = y;
    }

    /**
     * @return The comment on this block.
     */
    public String getComment() {
        return mComment;
    }

    /**
     * Set the comment on this block.
     *
     * @param comment The text of the comment.
     */
    public void setComment(@Nullable final String comment) {
        if (comment == mComment || (comment != null && comment.equals(mComment))) {
            return;
        }
        mController.groupAndFireEvents(new Runnable() {
            @Override
            public void run() {
                String oldValue = mComment;
                mComment = comment;

                // Add change event before notifying observers that might add their own events.
                mController.addPendingEvent(new BlocklyEvent.ChangeEvent(
                        BlocklyEvent.ELEMENT_COMMENT, Block.this, /* field */ null,
                        oldValue, mComment));
                fireUpdate(UPDATE_COMMENT);
            }
        });
    }

    /**
     * @return The set of inputs on this block.
     */
    public List<Input> getInputs() {
        return mInputList;
    }

    /**
     * @return True if and only if this block has inputs, i.e., {#link mInputList} is non-empty.
     */
    public boolean hasInputs() {
        return !mInputList.isEmpty();
    }

    /**
     * @return Whether the flag for displaying inputs in-line has been explicitly set.
     */
    public boolean getInputsInlineModified() {
        return mInputsInlineModified;
    }

    /**
     * @return The current state of the flag for displaying inputs in-line.
     */
    public boolean getInputsInline() {
        return mInputsInline;
    }

    /**
     * Set whether value inputs should be displayed inline (true), or on separate rows (false).
     * @param inputsInline True if value inputs should be show in a single line. Otherwise, false.
     */
    public void setInputsInline(final boolean inputsInline) {
        // Mark modified state for next serialization.
        mInputsInlineModified = true;

        if (inputsInline == mInputsInline) {
            return;
        }
        mController.groupAndFireEvents(new Runnable() {
            @Override
            public void run() {
                mInputsInline = inputsInline;

                // Add change event before notifying observers that might add their own events.
                mController.addPendingEvent(new BlocklyEvent.ChangeEvent(
                        BlocklyEvent.ELEMENT_INLINE, Block.this, mInputsInline));
                fireUpdate(UPDATE_INPUTS_INLINE);
            }
        });
    }

    /**
     * Searches through the block's list of inputs and returns the first one with the given name.
     *
     * @param targetName The name of the input to search for.
     *
     * @return The first Input with that name.
     */
    public Input getInputByName(String targetName) {
        for (int i = 0; i < mInputList.size(); i++) {
            if (mInputList.get(i).getName() != null
                    && mInputList.get(i).getName().equalsIgnoreCase(targetName)) {
                return mInputList.get(i);
            }
        }
        return null;
    }

    /**
     * @return The only value input on the block, or null if there are zero or more than one.
     */
    public Input getOnlyValueInput() {
        Input result = null;
        for (int i = 0; i < mInputList.size(); i++) {
            if (mInputList.get(i).getType() == Input.TYPE_VALUE) {
                if (result != null) {
                    return null;    // Found more than one value input
                }
                result = mInputList.get(i);
            }
        }
        return result;
    }

    /**
     * Searches through all of the fields on all of the block's inputs.  Returns the first field
     * with the given name.
     *
     * @param targetName The name of the field to search for.
     *
     * @return The first Field with that name.
     */
    public Field getFieldByName(String targetName) {
        Input input;
        Field field;
        for (int i = 0; i < mInputList.size(); i++) {
            input = mInputList.get(i);
            for (int j = 0; j < input.getFields().size(); j++) {
                field = input.getFields().get(j);
                if (field.getName() != null && field.getName().equalsIgnoreCase(targetName)) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * @return The block connected to this block's previous connection or null.
     */
    public Block getPreviousBlock() {
        return mPreviousConnection == null ? null : mPreviousConnection.getTargetBlock();
    }

    /**
     * @return The block connected to this block's next connection or null.
     */
    public Block getNextBlock() {
        return mNextConnection == null ? null : mNextConnection.getTargetBlock();
    }

    /**
     * @return The block's output Connection.
     */
    @Nullable
    public Connection getOutputConnection() {
        return mOutputConnection;
    }

    /**
     * @return The block's previous Connection.
     */
    @Nullable
    public Connection getPreviousConnection() {
        return mPreviousConnection;
    }

    /**
     * @return The block's next Connection.
     */
    @Nullable
    public Connection getNextConnection() {
        return mNextConnection;
    }

    /**
     * @return True if this is a shadow block, false otherwise.
     */
    public boolean isShadow() {
        return mIsShadow;
    }

    /**
     * @return True if this block can be dragged by the user, false otherwise.
     */
    public boolean isDraggable() {
        return !mIsShadow && mMovable;
    }

    /**
     * Creates a copy of this block and all inferior blocks connected to it.
     *
     * @return A new block tree with a copy of this block as the root.
     */
    public Block deepCopy() {
        try {
            String xml = BlocklyXmlHelper.writeBlockToXml(this,
                    IOOptions.WRITE_ALL_BLOCKS_WITHOUT_ID);
            return BlocklyXmlHelper.loadOneBlockFromXml(xml, mFactory);
        } catch (BlocklySerializerException | BlockLoadingException e) {
            // This error indicates something is very wrong with the serialization / deserialization
            // framework.  Allow this to bubble up as a RuntimeException.
            throw new IllegalStateException("Failed to copy blocks.", e);
        }
    }

    /**
     * Writes information about the editable parts of the block as XML.
     *
     * @param serializer The XmlSerializer to write to.
     * @param options I/O options.
     *
     * @throws IOException
     */
    public void serialize(XmlSerializer serializer, boolean rootBlock, IOOptions options)
            throws IOException {
        serializer.startTag(null, mIsShadow ? "shadow" : "block")
                .attribute(null, "type", mType);
        if (options.isBlockIdWritten()) {
            serializer.attribute(null, "id", mId);
        }

        // The position of the block only needs to be saved if it is a top level block.
        if (rootBlock) {
            serializer.attribute(null, "x", Float.toString(mPosition.x))
                    .attribute(null, "y", Float.toString(mPosition.y));
        }

        if (isCollapsed()) {
            serializer.attribute(null, "collapsed", "true");
        }
        if (!isDeletable() && !isShadow()) {
            serializer.attribute(null, "deletable", "false");
        }
        if (isDisabled()) {
            serializer.attribute(null, "disabled", "true");
        }
        if (!isEditable()) {
            serializer.attribute(null, "editable", "false");
        }
        if (!isMovable() && !isShadow()) {
            serializer.attribute(null, "movable", "false");
        }

        // Only serialize whether the inputs are internal or external if it has been explicitly
        // modified.  This can happen if it's set explicitly in json, if it's set in xml, or if
        // it was changed by the user in the program.
        if (mInputsInlineModified) {
            serializer.attribute(null, "inline", Boolean.toString(mInputsInline));
        }

        for (int i = 0; i < mInputList.size(); i++) {
            if (mInputList.get(i) != null) {
                mInputList.get(i).serialize(serializer, options);
            }
        }

        if (options.isBlockChildWritten() && getNextBlock() != null) {
            serializer.startTag(null, "next");
            getNextBlock().serialize(serializer, false, options);
            serializer.endTag(null, "next");
        }

        if (mMutator != null) {
            mMutator.serialize(serializer);
        }

        serializer.endTag(null, mIsShadow ? "shadow" : "block");
    }

    /**
     * @return The {@link Block} for the last non-shadow child in this sequence, possibly itself.
     */
    public Block getLastBlockInSequence() {
        Block last = this;
        Block next = this.getNextBlock();
        // Protect against loops by checking for dupes?
        while (next != null && !next.isShadow()) {
            last = next;
            next = last.getNextBlock();
        }
        return last;
    }

    /**
     * Walks the chain of blocks in this block, at each stage checking if there are multiple
     * value inputs.  If there is only one value input at each block, follows that input to the
     * next block.  If at any point there is more than one value input on a block or there are no
     * value inputs on a block, returns null. If the next block in the sequence is a shadow block
     * this returns the connection before the shadow.
     *
     * @return the {@link Connection} on the only input on the last block in the chain.
     */
    public Connection getLastUnconnectedInputConnection() {
        Block block = this;

        // Loop until there are no more singular, connected inputs.
        while (true) {
            Input onlyValueInput = block.getOnlyValueInput();
            if (onlyValueInput == null) {
                return null;
            }
            Connection conn = onlyValueInput.getConnection();
            if (conn == null) {
                return null;
            }
            if (!conn.isConnected()) {
                return conn;
            }
            block = conn.getTargetBlock();
            if (block.isShadow()) {
                return conn;
            }
        }
    }

    /**
     * Find the highest block in the hierarchy that this {@link Block} descends from.
     *
     * @return The highest block found.
     */
    public Block getRootBlock() {
        Block block = this;
        Block parent = block.getParentBlock();
        // Go up and left as far as possible.
        while (parent != null) {
            block = parent;
            parent = block.getParentBlock();
        }
        return block;
    }

    /**
     * @return The previous or output {@link Connection} on this block, or null.
     */
    @Nullable
    public Connection getUpwardsConnection() {
        if (mPreviousConnection != null) {
            return mPreviousConnection;
        }
        if (mOutputConnection != null) {
            return mOutputConnection;
        }
        return null;
    }

    /**
     * @return The next or input {@link Connection} this block is connected to, or null.
     */
    @Nullable
    public Connection getParentConnection() {
        Connection upwards = getUpwardsConnection();
        return upwards == null ? null : upwards.getTargetConnection();
    }

    /**
     * @return The block connected to the output or previous {@link Connection}, if present.
     *         Otherwise null.
     */
    public Block getParentBlock() {
        Connection parentConnection = getParentConnection();
        return parentConnection == null ? null : parentConnection.getBlock();
    }

    /**
     * Configures whether this block should be a shadow block. This should only be called during
     * block initialization.
     *
     * @param isShadow If true the block will act as a shadow.
     */
    public void setShadow(boolean isShadow) {
        if (mIsShadow == isShadow) {
            return;
        }

        // Unsupported: Changing while connected to the parent (Connection target type, etc.)
        Connection upConnection = getUpwardsConnection();
        if (upConnection != null && upConnection.getTargetConnection() != null) {
            throw new IllegalStateException(
                    "Cannot change block shadow state while connected to parent.");
        }
        if (isShadow) {
            if (containsVariableField(mInputList)) {
                throw new IllegalStateException("Shadow blocks cannot contain variable fields.");
            }

            // Shadow blocks cannot have non-shadow children.
            int inputCount = mInputList == null ? 0 : mInputList.size();
            for (int i = 0; i < inputCount; ++i) {
                Input input = mInputList.get(i);
                Block child = input.getConnectedBlock();  // Not the shadow connection.
                if (child != null && !child.isShadow()) {
                    throw new IllegalStateException(
                            "Cannot change block to shadow while non-shadow children are connected."
                    );
                }
            }
        }

        // State change is valid. Proceed.
        mIsShadow = isShadow;
        fireUpdate(UPDATE_IS_SHADOW);
    }

    /**
     * @return The block's {@link Mutator} id, if any. Otherwise null.
     * @see BlockFactory#applyMutator(String, Block)
     */
    @Nullable
    public final String getMutatorId() {
        return mMutatorId;
    }

    /**
     * @return The block's {@link Mutator}, if any. Otherwise null.
     * @see BlockFactory#applyMutator(String, Block)
     */
    @Nullable
    public final Mutator getMutator() {
        return mMutator;
    }

    /**
     * {@code reshape()} updates the inputs and all connections with potentially new values,
     * changing the shape of the block. This method should only be called by the constructor, or
     * {@link Mutator}s.
     * <p/>
     * Changes to {@link Input} and their {@link Field} change by updating the whole input list.
     * Inputs can be reused, and must be reused if blocks are to remain connected to a block.
     * Removed inputs must be previously disconnected (moved or deleted), and added inputs must also
     * be empty (i.e., not connect to child blocks).
     * <p/>
     * Similarly, {@link Connection}s that should remain connected should be reused, and
     * added/removed connections must not be connected to another block. All connections must be
     * constructed with the correct connection type for their position.
     *
     * @param newInputList The new list of inputs.
     * @param updatedOutput The updated output connection, if any.
     * @param updatedPrev The updated previous connection, if any.
     * @param updatedNext The updated next connection, if any.
     */
    public void reshape(@Nullable List<Input> newInputList,
                        @Nullable Connection updatedOutput,
                        @Nullable Connection updatedPrev,
                        @Nullable Connection updatedNext) {
        if (updatedOutput != null) {
            if (updatedPrev != null) {
                throw new IllegalArgumentException(
                        "A block cannot have both an output connection and a previous connection.");
            }
            if (updatedOutput.getType() != Connection.CONNECTION_TYPE_OUTPUT) {
                throw new IllegalArgumentException(
                        "updatedOutput Connection type is not CONNECTION_TYPE_OUTPUT");
            }
        }
        if (updatedPrev != null && updatedPrev.getType() != Connection.CONNECTION_TYPE_PREVIOUS) {
            throw new IllegalArgumentException(
                    "updatedPrev Connection type is not CONNECTION_TYPE_PREVIOUS");
        }
        if (updatedNext != null && updatedNext.getType() != Connection.CONNECTION_TYPE_NEXT) {
            throw new IllegalArgumentException(
                    "updatedNext Connection type is not CONNECTION_TYPE_NEXT");
        }

        if (newInputList == null) {
            newInputList = Collections.<Input>emptyList();
        }

        List<Connection> connectionList = new ArrayList<>();
        List<Input> oldInputs = mInputList;

        for (Input in : oldInputs) {
            if (!newInputList.contains(in)) {
                if (in.getConnectedBlock() != null) {
                    // This is a critical failure, as it may leave inputs in an invalid state if
                    // another input was already removed (i.e., setBlock(null) was already called).
                    throw new IllegalStateException(
                            "Cannot remove input \"" + in.getName() + "\" while connected.");
                }
                in.setBlock(null); // Reset the block reference in removed Inputs and Fields.
            }
        }
        for (Input in : newInputList) {
            if (!oldInputs.contains(in)) {
                if (in.getConnectedBlock() != null) {
                    // This is a critical failure, as it may leave inputs in an invalid state if
                    // an old input was removed above (i.e., setBlock(null) was already called).
                    throw new IllegalStateException(
                            "Cannot add input \"" + in.getName() + "\" while connected.");
                }
                in.setBlock(this);
            }
            Connection inputConn = in.getConnection();
            if (inputConn != null) {
                connectionList.add(inputConn);
            }
        }

        if (updatedOutput != null) {
            updatedOutput.setBlock(this);
            connectionList.add(updatedOutput);
        }
        if (updatedPrev != null) {
            updatedPrev.setBlock(this);
            connectionList.add(updatedPrev);
        }
        if (updatedNext != null) {
            updatedNext.setBlock(this);
            connectionList.add(updatedNext);
        }

        mInputList = Collections.unmodifiableList(newInputList);
        mOutputConnection = updatedOutput;
        mPreviousConnection = updatedPrev;
        mNextConnection = updatedNext;
        mConnectionList = Collections.unmodifiableList(connectionList);

        fireUpdate(UPDATE_INPUTS_FIELDS_CONNECTIONS);
    }

    /**
     * This method returns a string describing this Block in developer terms (type
     * name and ID; English only). Intended to on be used in console logs and errors.
     * @return The description.
     */
    public String toString() {
        String description = (mIsShadow ? "shadow" : "block");
        if (mType != null) {
            description = "\"" + mType + "\" " + description;  // Prefix
        }
        if (mId != null) {
            description += " (id=\"" + mId + "\")"; // Postfix
        }
        return description;
    };

    /**
     * Sets the mutator for this block.  Called from BlockFractory, and can only be called once (for
     * now).
     * @param mutator The mutator implementation.
     * @param id The mutator id, used to pair with a UI implementation.
     */
    /*package private*/ void setMutator(@NonNull Mutator mutator, String id) {
        if (mMutator != null) {
            throw new IllegalStateException("Cannot change mutators on a block.");
        }
        mMutator = mutator;
        mMutatorId = id;
        mutator.onAttached(this);
    }

    /**
     * Sets the block's parent {@link BlockContainer}.
     *
     * @param container
     */
    // TODO(567): Call when root block is added to a workspace
    // TODO(567): Call when attached to parent block next, statement, or previous
    // TODO(567): Call when attached as root block toolbox BlocklyCategory
    // TODO(567): Call when attached as root block trash BlocklyCategory
    /*package private*/ void setBlockContainer(BlockContainer container) {
        mParentContainer = container;
    }

    /**
     * Connects to given child and shadow (even if occluded), or throws a descriptive
     * BlockLoadingException for an invalid connection.
     * @param tagName The string name of the connection, as seen in XML.
     * @param thisConn The connection point on this block.
     * @param child The child block to connect.
     * @param shadow The child shadow to connect.
     * @throws BlockLoadingException If any connection fails.
     */
    void connectOrThrow(String tagName, Connection thisConn, Block child, Block shadow)
            throws BlockLoadingException {
        if (child != null) {
            if (mIsShadow) {
                throw new BlockLoadingException(
                        this + " cannot be a parent to non-shadow " + child);
            }
            Connection childConn = child.getUpwardsConnection();
            try {
                thisConn.connect(childConn);
            } catch (IllegalArgumentException e) {
                throw new BlockLoadingException(
                        this + ": Invalid " + tagName + " connection to " + child, e);
            }
        }
        if (shadow != null) {
            Connection shadowConn = shadow.getUpwardsConnection();
            try {
                thisConn.setShadowConnection(shadowConn);
                if (!thisConn.isConnected()) {
                    // If there is no standard child block, so connect the shadow
                    thisConn.connect(shadowConn);
                }
            } catch (IllegalArgumentException e) {
                throw new BlockLoadingException(
                        this + ": Invalid " + tagName + " shadow connection to " + child, e);
            }
        }
    }

    /**
     * Makes a copy of any blocks connected to the source connection and adds the copies to the
     * destination connection. The source and destination Connections must be of the same type and
     * must be either a next or input connection.
     *
     * @param sourceConnection The connection to copy blocks from.
     * @param destConnection The connection to add copied blocks to.
     */
    private void copyConnection(Connection sourceConnection, Connection destConnection) {
        if (sourceConnection.getType() != destConnection.getType() ||
                (sourceConnection.getType() != Connection.CONNECTION_TYPE_NEXT &&
                        sourceConnection.getType() != Connection.CONNECTION_TYPE_INPUT)) {
            throw new IllegalArgumentException(
                    "Connection types must match and must be a superior connection.");
        }
        Block copy = null;
        if (sourceConnection.getShadowBlock() != null) {
            // Make a copy of the shadow if we have one and set it on the connection
            copy = sourceConnection.getShadowBlock().deepCopy();
            if (destConnection.getType() == Connection.CONNECTION_TYPE_NEXT) {
                destConnection.setShadowConnection(copy.getPreviousConnection());
            } else if (destConnection.getType() == Connection.CONNECTION_TYPE_INPUT) {
                destConnection.setShadowConnection(copy.getOutputConnection());
            }
        }

        if (sourceConnection.getTargetBlock() != null) {
            // If a block other than the shadow was connected make a copy of that
            if (sourceConnection.getTargetBlock() != sourceConnection.getShadowBlock()) {
                copy = sourceConnection.getTargetBlock().deepCopy();
            }
            // Connect a copy of whichever block was connected to the source
            if (destConnection.getType() == Connection.CONNECTION_TYPE_NEXT) {
                destConnection.connect(copy.getPreviousConnection());
            } else if (destConnection.getType() == Connection.CONNECTION_TYPE_INPUT) {
                destConnection.connect(copy.getOutputConnection());
            }
        }
    }

    /**
     * Breaks a block message up into args and text. The returned Strings should all either
     * exactly match "^%\\d+$" if they are an arg or else are just text for a label. %[0-9]+ will
     * always be treated as an argument regardless of where in the string it appears, unless the
     * % is escaped (eg "Escaped %%5 has no args")
     *
     * @param message The message to tokenize.
     * @return A list of Strings that are either an arg or plain text.
     */
    // package scoped
    static List<String> tokenizeMessage(String message) {
        ArrayList<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(message)) {
            return result;
        }
        boolean foundPercent = false;
        int lastSplit = 0;
        int lastPercent = -1;

        for (int i = 0, length = message.length(); i < length; i++) {
            char currChar = message.charAt(i);
            if (currChar == '%') {
                if (i + 1 < length) {
                    char nextChar = message.charAt(i + 1);
                    if (nextChar == '%' || !Character.isDigit(nextChar)) {
                        // If we have %% or this is not an arg don't pull it out of the string.
                        i++;
                        continue;
                    }
                } else {
                    // Done processing the string. Final % will be included in the last token.
                    continue;
                }
                foundPercent = true;
                lastPercent = i;
            } else if (foundPercent) {
                if (Character.isDigit(currChar)) {
                    continue;
                } else {
                    String potentialText = message.substring(lastSplit, lastPercent).trim();
                    if (!TextUtils.isEmpty(potentialText)) {
                        result.add(potentialText);
                    }
                    result.add(message.substring(lastPercent, i));
                    lastSplit = i;
                    foundPercent = false;
                }
            }
        }
        if (lastSplit != message.length() - 1) {
            // We have remaining pieces to split
            if (lastPercent > lastSplit) {
                String potentialText = message.substring(lastSplit, lastPercent).trim();
                if (!TextUtils.isEmpty(potentialText)) {
                    result.add(potentialText);
                }
                result.add(message.substring(lastPercent, message.length()));
            } else {
                String potentialText = message.substring(lastSplit, message.length()).trim();
                if (!TextUtils.isEmpty(potentialText)) {
                    result.add(potentialText);
                }
            }
        }

        return result;
    }

    /**
     * @return True if any input in this block includes a variable field. Otherwise false.
     */
    boolean containsVariableField() {
        return containsVariableField(mInputList);
    }

    /**
     * Notifies Observers of a structure change.
     * @param updateStateMask A bit mask of {@link UpdateState} bits for the updated parts.
     */
    protected void fireUpdate(@UpdateState int updateStateMask) {
        for (Observer observer: mObservers) {
            observer.onBlockUpdated(this, updateStateMask);
        }
    }

    /**
     * @param inputs {@link Input}s to check for variable fields.
     * @return True if any input in {@code inputs} includes a variable field. Otherwise false.
     */
    private static boolean containsVariableField(List<Input> inputs) {
        // Verify there's not a variable field
        int inputCount = inputs.size();
        for (int i = 0; i < inputCount; i++) {
            Input in = inputs.get(i);
            List<Field> fields = in.getFields();
            int fieldCount = fields.size();
            for (int j = 0; j < fieldCount; j++) {
                Field f = fields.get(j);
                if (f.getType() == Field.TYPE_VARIABLE) {
                    return true;
                }
            }
        }
        return false;
    }
}
