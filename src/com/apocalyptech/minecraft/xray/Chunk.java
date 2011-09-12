/**
 * Copyright (c) 2010-2011, Vincent Vollers and Christopher J. Kucera
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Minecraft X-Ray team nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL VINCENT VOLLERS OR CJ KUCERA BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.apocalyptech.minecraft.xray;

import java.lang.Math;
import java.util.ArrayList;

import org.lwjgl.opengl.GL11;

import com.apocalyptech.minecraft.xray.dtf.ShortArrayTag;
import com.apocalyptech.minecraft.xray.dtf.ByteArrayTag;
import com.apocalyptech.minecraft.xray.dtf.CompoundTag;
import com.apocalyptech.minecraft.xray.dtf.StringTag;
import com.apocalyptech.minecraft.xray.dtf.ListTag;
import com.apocalyptech.minecraft.xray.dtf.IntTag;
import com.apocalyptech.minecraft.xray.dtf.Tag;

import static com.apocalyptech.minecraft.xray.MinecraftConstants.*;

/**
 * Chunk functions, including the meat of our rendering stuffs
 *
 * TODO: There are a lot of functions that do very similar things in here, it would be
 * good to consolidate some of those.  I don't know why it took me so long to come
 * up with the current implementation of renderVertical and renderHorizontal - I suspect
 * that much of the rendering code would be improved by moving to those if possible.
 */
public class Chunk {
	private int displayListNum;
	private int transparentListNum;
	private int selectedDisplayListNum;
	public int x;
	public int z;
	public boolean isDirty;
	public boolean isSelectedDirty;
	public boolean isOnMinimap;
	private CompoundTag chunkData;
	private ShortArrayTag blockData;
	private ByteArrayTag mapData;
	private ArrayList<PaintingEntity> paintings;
	
	private MinecraftLevel level;

	private final float fence_postsize = .125f;
	private final float fence_postsize_h = fence_postsize/2f;
	private final float fence_slat_height = .1875f;
	private final float fence_top_slat_offset = .375f;
	private final float fence_slat_start_offset = -.125f;
	
	public Chunk(MinecraftLevel level, Tag data) {
		
		this.level = level;
		this.chunkData = (CompoundTag) data;
		this.isOnMinimap = false;

		CompoundTag levelTag = (CompoundTag) chunkData.value.get(0); // first tag
		IntTag xPosTag = (IntTag) levelTag.getTagWithName("xPos");
		IntTag zPosTag = (IntTag) levelTag.getTagWithName("zPos");
		
		paintings = new ArrayList<PaintingEntity>();
		ListTag entities = (ListTag)levelTag.getTagWithName("Entities");
		StringTag entity_id;
		CompoundTag ct;
		for (Tag t : entities.value)
		{
			ct = (CompoundTag)t;
			entity_id = (StringTag) ct.getTagWithName("id");
			if (entity_id.value.equalsIgnoreCase("painting"))
			{
				paintings.add(new PaintingEntity(ct));
			}
		}
		
		this.x = xPosTag.value;
		this.z = zPosTag.value;
		
		blockData = (ShortArrayTag) levelTag.getTagWithName("Blocks");
		mapData = (ByteArrayTag) levelTag.getTagWithName("Data");
		
		this.isDirty = true;
		this.isSelectedDirty = true;

		displayListNum = GL11.glGenLists(1);
		selectedDisplayListNum = GL11.glGenLists(1);
		transparentListNum = GL11.glGenLists(1);
		
		//System.out.println(data);
		//System.exit(0);
	}
	
	public CompoundTag getChunkData() {
		return this.chunkData;
	}
	
	public ShortArrayTag getMapData() {
		return this.blockData;
	}

	/**
	 * Gets the Block ID of the block immediately to the north.  This might
	 * load in the adjacent chunk, if needed.  Will return -1 if that adjacent
	 * chunk can't be found.
	 */
	private short getAdjNorthBlockId(int x, int y, int z, int blockOffset)
	{
		if (x > 0)
		{
			return blockData.value[blockOffset-BLOCKSPERCOLUMN];
		}
		else
		{
			Chunk otherChunk = level.getChunk(this.x-1, this.z);
			if (otherChunk == null)
			{
				return -1;
			}
			else
			{
				return otherChunk.getBlock(15, y, z);
			}
		}
	}

	/**
	 * Gets the Block ID of the block immediately to the south.  This might
	 * load in the adjacent chunk, if needed.  Will return -1 if that adjacent
	 * chunk can't be found.
	 */
	private short getAdjSouthBlockId(int x, int y, int z, int blockOffset)
	{
		if (x < 15)
		{
			return blockData.value[blockOffset+BLOCKSPERCOLUMN];
		}
		else
		{
			Chunk otherChunk = level.getChunk(this.x+1, this.z);
			if (otherChunk == null)
			{
				return -1;
			}
			else
			{
				return otherChunk.getBlock(0, y, z);
			}
		}
	}

	/**
	 * Gets the Block ID of the block immediately to the east.  This might
	 * load in the adjacent chunk, if needed.  Will return -1 if that adjacent
	 * chunk can't be found.
	 */
	private short getAdjEastBlockId(int x, int y, int z, int blockOffset)
	{
		if (z > 0)
		{
			return blockData.value[blockOffset-BLOCKSPERROW];
		}
		else
		{
			Chunk otherChunk = level.getChunk(this.x, this.z-1);
			if (otherChunk == null)
			{
				return -1;
			}
			else
			{
				return otherChunk.getBlock(x, y, 15);
			}
		}
	}

	/**
	 * Gets the Block ID of the block immediately to the west.  This might
	 * load in the adjacent chunk, if needed.  Will return -1 if that adjacent
	 * chunk can't be found.
	 */
	private short getAdjWestBlockId(int x, int y, int z, int blockOffset)
	{
		if (z < 15)
		{
			return blockData.value[blockOffset+BLOCKSPERROW];
		}
		else
		{
			Chunk otherChunk = level.getChunk(this.x, this.z+1);
			if (otherChunk == null)
			{
				return -1;
			}
			else
			{
				return otherChunk.getBlock(x, y, 0);
			}
		}
	}
	
	/**
	 * Render something which is a North/South face.
	 */
	public void renderNorthSouth(int t, float x, float y, float z) {
		this.renderNorthSouth(t, x, y, z, 0.5f, 0.5f);
	}
	
	/**
	 * Render something which is a North/South face.
	 * 
	 * @param t Texture to render
	 * @param x
	 * @param y
	 * @param z
	 * @param yHeightOffset How tall this block is.  0.5f is the usual, specify 0 for half-height
	 * @param xzScale How large the rest of the block is.  0.5f is full-size, 0.1 would be tiny.
	 */
	public void renderNorthSouth(int t, float x, float y, float z, float yHeightOffset, float xzScale) {
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t], precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x-xzScale, y+yHeightOffset, z+xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x-xzScale, y+yHeightOffset, z-xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t],precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x-xzScale, y-xzScale, z+xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x-xzScale, y-xzScale, z-xzScale);
		GL11.glEnd();
	}
	
	/**
	 * Renders a floor tile which is also rotated
	 * 
	 * @param t
	 * @param x
	 * @param y
	 * @param z
	 * @param turns The number of clockwise 90-degree turns to rotate the texture
	 */
	public void renderTopDownRotate(int t, float x, float y, float z, int turns)
	{
		float scale = 0.5f;
		float tx = precalcSpriteSheetToTextureX[t];
		float ty = precalcSpriteSheetToTextureY[t];
		float x1, y1;
		float x2, y2;
		float x3, y3;
		float x4, y4;
		
		switch (turns)
		{
			case 0:
				x1 = tx;       y1 = ty;
				x2 = tx+TEX16; y2 = ty;
				x3 = tx;       y3 = ty+TEX32;
				x4 = tx+TEX16; y4 = ty+TEX32;
				break;
			case 1:
				x1 = tx+TEX16; y1 = ty;
				x2 = tx+TEX16; y2 = ty+TEX32;
				x3 = tx;       y3 = ty;
				x4 = tx;       y4 = ty+TEX32;
				break;
			case 2:
				x1 = tx+TEX16; y1 = ty+TEX32;
				x2 = tx;       y2 = ty+TEX32;
				x3 = tx+TEX16; y3 = ty;
				x4 = tx;       y4 = ty;
				break;
			case 3:
			default:
				x1 = tx;       y1 = ty+TEX32;
				x2 = tx;       y2 = ty;
				x3 = tx+TEX16; y3 = ty+TEX32;
				x4 = tx+TEX16; y4 = ty;
				break;
		}
		
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(x1, y1);
			GL11.glVertex3f(x-scale, y-scale, z+scale);
	
			GL11.glTexCoord2f(x2, y2);
			GL11.glVertex3f(x-scale, y-scale, z-scale);
	
			GL11.glTexCoord2f(x3, y3);
			GL11.glVertex3f(x+scale, y-scale, z+scale);
	
			GL11.glTexCoord2f(x4, y4);
			GL11.glVertex3f(x+scale, y-scale, z-scale);
		GL11.glEnd();
		
	}
	
	/**
	 * Render the top or bottom of a block, depending on how we're looking at it.
	 */
	public void renderTopDown(int t, float x, float y, float z) {
		this.renderTopDown(t, x, y, z, 0.5f);
	}
	
	/**
	 * Render the top or bottom of a block, depending on how we're looking at it.
	 * 
	 * @param t The texture ID to draw
	 * @param x
	 * @param y
	 * @param z
	 * @param scale ".5" is a full-sized block, ".1" would be tiny.
	 */
	public void renderTopDown(int t, float x, float y, float z, float scale) {
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t], precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x-scale, y-scale, z+scale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x-scale, y-scale, z-scale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t], precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x+scale, y-scale, z+scale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x+scale, y-scale, z-scale);
		GL11.glEnd();
	}
	

	/**
	 * Renders something which is a West/East face.
	 */
	public void renderWestEast(int t, float x, float y, float z) {
		this.renderWestEast(t, x, y, z, 0.5f, 0.5f);
	}
	
	/**
	 * Renders something which is a West/East face.
	 * 
	 * @param t Texture to draw
	 * @param x
	 * @param y
	 * @param z
	 * @param yHeightOffset How tall this block is.  0.5f is the usual, specify 0 for half-height
	 * @param xzScale How large the rest of the block is.  0.5f is full-size, 0.1 would be tiny.
	 */
	public void renderWestEast(int t, float x, float y, float z, float yHeightOffset, float xzScale) {
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t], precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x-xzScale, y+yHeightOffset, z-xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]);
			GL11.glVertex3f(x+xzScale, y+yHeightOffset, z-xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t], precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x-xzScale, y-xzScale, z-xzScale);
	
			GL11.glTexCoord2f(precalcSpriteSheetToTextureX[t]+TEX16, precalcSpriteSheetToTextureY[t]+TEX32);
			GL11.glVertex3f(x+xzScale, y-xzScale, z-xzScale);
		GL11.glEnd();
	}


	/**
	 * Renders a vertical texture with a full square texture.
	 */
	public void renderVertical(int t, float x1, float z1, float x2, float z2, float y, float height) {
		renderVertical(t, x1, z1, x2, z2, y, height, 16, 16, 0, 0);
	}

	/**
	 * Renders a somewhat-arbitrary vertical rectangle.  Pass in (x, z) pairs for the endpoints,
	 * and information about the height.  The texture variables given are in terms of 1/16ths of
	 * the texture square, which means that for the default Minecraft 16x16 texture, they're in
	 * pixels.
	 * 
	 * @param t Texture to draw
	 * @param x1
	 * @param z1
	 * @param x2
	 * @param z2
	 * @param y	The lower part of the rectangle
	 * @param height Height of the rectangle.
	 */
	public void renderVertical(int t, float x1, float z1, float x2, float z2, float y, float height, int tex_width, int tex_height, int tex_start_x, int tex_start_y) {

		float bx = precalcSpriteSheetToTextureX[t]+(TEX256*tex_start_x);
		float by = precalcSpriteSheetToTextureY[t]+(TEX512*tex_start_y);

		float tdx = TEX256*tex_width;
		float tdy = TEX512*tex_height;

		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x1, y+height, z1);
	
			GL11.glTexCoord2f(bx+tdx, by);
			GL11.glVertex3f(x2, y+height, z2);
	
			GL11.glTexCoord2f(bx, by+tdy);
			GL11.glVertex3f(x1, y, z1);
	
			GL11.glTexCoord2f(bx+tdx, by+tdy);
			GL11.glVertex3f(x2, y, z2);
		GL11.glEnd();
	}
	
	/**
	 * Renders a nonstandard vertical rectangle (nonstandard referring primarily to
	 * the texture size (ie: when we're not pulling a single element out of a 16x16
	 * grid).  This differs from renderVertical also in that we specify two full
	 * (x, y, z) coordinates for the bounds, instead of passing in y and a height.
	 * Texture coordinates are passed in as the usual float from 0 to 1.
	 * 
	 * @param tx X index within the texture
	 * @param ty Y index within the texture
	 * @param tdx Width of texture
	 * @param tdy Height of texture
	 * @param x1
	 * @param y1
	 * @param z1
	 * @param x2
	 * @param y2
	 * @param z2
	 */
	public void renderNonstandardVertical(float tx, float ty, float tdx, float tdy, float x1, float y1, float z1, float x2, float y2, float z2)
	{
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(tx, ty);
			GL11.glVertex3f(x1, y1, z1);
			
			GL11.glTexCoord2f(tx+tdx, ty);
			GL11.glVertex3f(x2, y1, z2);
			
			GL11.glTexCoord2f(tx, ty+tdy);
			GL11.glVertex3f(x1, y2, z1);
			
			GL11.glTexCoord2f(tx+tdx, ty+tdy);
			GL11.glVertex3f(x2, y2, z2);
		GL11.glEnd();
	}
	
	/**
	 * Renders a nonstandard vertical rectangle (nonstandard referring primarily to
	 * the texture size (ie: when we're not pulling a single element out of a 16x16
	 * grid).  This differs from renderVertical also in that we specify two full
	 * (x, y, z) coordinates for the bounds, instead of passing in y and a height.
	 * Texture coordinates are passed in as the usual float from 0 to 1.
	 *
	 * Additionally, this method will rotate the texture while drawing; I needed this
	 * for Pistons, specifically - will probably come in handy elsewhere too.
	 * 
	 * @param tx X index within the texture
	 * @param ty Y index within the texture
	 * @param tdx Width of texture
	 * @param tdy Height of texture
	 * @param x1
	 * @param y1
	 * @param z1
	 * @param x2
	 * @param y2
	 * @param z2
	 */
	public void renderNonstandardVerticalTexRotate(float tx, float ty, float tdx, float tdy, float x1, float y1, float z1, float x2, float y2, float z2)
	{
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(tx+tdx, ty);
			GL11.glVertex3f(x1, y1, z1);
			
			GL11.glTexCoord2f(tx+tdx, ty+tdy);
			GL11.glVertex3f(x2, y1, z2);
			
			GL11.glTexCoord2f(tx, ty);
			GL11.glVertex3f(x1, y2, z1);
			
			GL11.glTexCoord2f(tx, ty+tdy);
			GL11.glVertex3f(x2, y2, z2);
		GL11.glEnd();
	}

	/**
	 * Renders a "default" horizontal rectangle, using a full square for the texture.
	 */
	public void renderHorizontal(int t, float x1, float z1, float x2, float z2, float y) {
		renderHorizontal(t, x1, z1, x2, z2, y, 16, 16, 0, 0, false);
	}
	
	/**
	 * Renders an arbitrary horizontal rectangle (will be orthogonal).  The texture parameters
	 * are specified in terms of 1/16ths of the texture (which equates to one pixel, when using
	 * the default 16x16 Minecraft texture.
	 *
	 * @param t
	 * @param x1
	 * @param z1
	 * @param x2
	 * @param z2
	 * @param y
	 */
	public void renderHorizontal(int t, float x1, float z1, float x2, float z2, float y, int tex_width, int tex_height, int tex_start_x, int tex_start_y, boolean flip_tex) {

		float bx = precalcSpriteSheetToTextureX[t]+(TEX256*tex_start_x);
		float by = precalcSpriteSheetToTextureY[t]+(TEX512*tex_start_y);

		float tdx = TEX256*tex_width;
		float tdy = TEX512*tex_height;

		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			
			if (flip_tex)
			{
				GL11.glTexCoord2f(bx, by);
				GL11.glVertex3f(x1, y, z2);
		
				GL11.glTexCoord2f(bx+tdx, by);
				GL11.glVertex3f(x2, y, z2);
		
				GL11.glTexCoord2f(bx, by+tdy);
				GL11.glVertex3f(x1, y, z1);
		
				GL11.glTexCoord2f(bx+tdx, by+tdy);
				GL11.glVertex3f(x2, y, z1);
			}
			else
			{
				GL11.glTexCoord2f(bx, by);
				GL11.glVertex3f(x1, y, z1);
		
				GL11.glTexCoord2f(bx+tdx, by);
				GL11.glVertex3f(x1, y, z2);
		
				GL11.glTexCoord2f(bx, by+tdy);
				GL11.glVertex3f(x2, y, z1);
		
				GL11.glTexCoord2f(bx+tdx, by+tdy);
				GL11.glVertex3f(x2, y, z2);
			}
		GL11.glEnd();
	}
	
	/**
	 * Render a surface on a horizontal plane; pass in all four verticies.  This can result,
	 * obviously, in non-rectangular and non-orthogonal shapes.
	 * 
	 * @param t
	 * @param x1
	 * @param z1
	 * @param x2
	 * @param z2
	 * @param x3
	 * @param z3
	 * @param x4
	 * @param z4
	 * @param y
	 */
	public void renderHorizontalAskew(int t, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y) {

		float bx = precalcSpriteSheetToTextureX[t];
		float by = precalcSpriteSheetToTextureY[t];

		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x1, y, z1);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x2, y, z2);
	
			GL11.glTexCoord2f(bx, by+TEX32);
			GL11.glVertex3f(x3, y, z3);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x4, y, z4);
		GL11.glEnd();
	}
	
	/**
	 * Renders a nonstandard horizontal rectangle (nonstandard referring primarily to
	 * the texture size (ie: when we're not pulling a single element out of a 16x16
	 * grid).
	 * 
	 * @param tx X index within the texture
	 * @param ty Y index within the texture
	 * @param tdx Width of texture
	 * @param tdy Height of texture
	 * @param x1
	 * @param z1
	 * @param x2
	 * @param z2
	 * @param y
	 */
	public void renderNonstandardHorizontal(float tx, float ty, float tdx, float tdy, float x1, float z1, float x2, float z2, float y) {
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(tx, ty);
			GL11.glVertex3f(x1, y, z1);
	
			GL11.glTexCoord2f(tx+tdx, ty);
			GL11.glVertex3f(x1, y, z2);
	
			GL11.glTexCoord2f(tx, ty+tdy);
			GL11.glVertex3f(x2, y, z1);
	
			GL11.glTexCoord2f(tx+tdx, ty+tdy);
			GL11.glVertex3f(x2, y, z2);
		GL11.glEnd();
	}

	/**
	 * Renders a nonstandard horizontal rectangle (nonstandard referring primarily to
	 * the texture size (ie: when we're not pulling a single element out of a 16x16
	 * grid).
	 *
	 * Additionally, this method will rotate the texture while drawing; I needed this
	 * for Pistons, specifically - will probably come in handy elsewhere too.
	 * 
	 * @param tx X index within the texture
	 * @param ty Y index within the texture
	 * @param tdx Width of texture
	 * @param tdy Height of texture
	 * @param x1
	 * @param z1
	 * @param x2
	 * @param z2
	 * @param y
	 */
	public void renderNonstandardHorizontalTexRotate(float tx, float ty, float tdx, float tdy, float x1, float z1, float x2, float z2, float y) {
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(tx+tdx, ty);
			GL11.glVertex3f(x1, y, z1);
	
			GL11.glTexCoord2f(tx+tdx, ty+tdy);
			GL11.glVertex3f(x1, y, z2);
	
			GL11.glTexCoord2f(tx, ty);
			GL11.glVertex3f(x2, y, z1);
	
			GL11.glTexCoord2f(tx, ty+tdy);
			GL11.glVertex3f(x2, y, z2);
		GL11.glEnd();
	}

	/**
	 * Given a whole mess of coordinates, draws an arbitrary rectangle
	 * 
	 * @param t
	 * @param x1
	 * @param y1
	 * @param z1
	 * @param x2
	 * @param y2
	 * @param z2
	 * @param x3
	 * @param y3
	 * @param z3
	 * @param x4
	 * @param y4
	 * @param z4
	 */
	public void renderArbitraryRect(int t,
			float x1, float y1, float z1,
			float x2, float y2, float z2,
			float x3, float y3, float z3,
			float x4, float y4, float z4)
	{
		float tx = precalcSpriteSheetToTextureX[t];
		float ty = precalcSpriteSheetToTextureY[t];

		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(tx, ty);
			GL11.glVertex3f(x1, y1, z1);
	
			GL11.glTexCoord2f(tx+TEX16, ty);
			GL11.glVertex3f(x2, y2, z2);
	
			GL11.glTexCoord2f(tx, ty+TEX32);
			GL11.glVertex3f(x3, y3, z3);
	
			GL11.glTexCoord2f(tx+TEX16, ty+TEX32);
			GL11.glVertex3f(x4, y4, z4);
		GL11.glEnd();
		
	}
	
	/**
	 * Renders the side of a stair piece that runs East/West.  Verticies are in the following order:
	 * <pre>
	 *         6---5
	 *         |   |
	 *     2---4   |
	 *     |       |
	 *     1-------3
	 * </pre>
	 * 
	 * Note that the function is "WestEast" which corresponds to the stair direction;
	 * this will actually draw the face on the north or south sides.
	 * 
	 * @param t
	 * @param x
	 * @param y
	 * @param z
	 */
	public void renderStairSideWestEast(int t, float x, float y, float z, boolean swapZ) {
		
		float bx = precalcSpriteSheetToTextureX[t];
		float by = precalcSpriteSheetToTextureY[t];
		
		float zoff=0.5f;
		if (swapZ)
		{
			zoff = -0.5f;
		}
		
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
		
			GL11.glTexCoord2f(bx, by+TEX32);
			GL11.glVertex3f(x-0.5f, y-0.5f, z+zoff);
	
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x-0.5f, y, z+zoff);
			
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x-0.5f, y-0.5f, z-zoff);
	
			GL11.glTexCoord2f(bx+TEX32, by+TEX64);
			GL11.glVertex3f(x-0.5f, y, z);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x-0.5f, y+0.5f, z-zoff);
			
			GL11.glTexCoord2f(bx+TEX32, by);
			GL11.glVertex3f(x-0.5f, y+0.5f, z);

		GL11.glEnd();
	}	

	/**
	 * Renders the stair surface, for a stair running West/East
	 * 
	 * @param t Texture to draw
	 * @param x
	 * @param y
	 * @param z
	 * @param swapX
	 */
	public void renderStairSurfaceWestEast(int t, float x, float y, float z, boolean swapZ) {
		
		float bx = precalcSpriteSheetToTextureX[t];
		float by = precalcSpriteSheetToTextureY[t];
		
		float zoff = 0.5f;
		if (swapZ)
		{
			zoff = -0.5f;
		}
		
		// Lower Step surface
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x+0.5f, y, z+zoff);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x-0.5f, y, z+zoff);
	
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x+0.5f, y, z);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x-0.5f, y, z);
		GL11.glEnd();

		// Lower Step Side
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x+0.5f, y, z+zoff);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x-0.5f, y, z+zoff);
	
			GL11.glTexCoord2f(bx,by+TEX32);
			GL11.glVertex3f(x+0.5f, y-0.5f, z+zoff);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x-0.5f, y-0.5f, z+zoff);
		GL11.glEnd();

		// Higher Step surface
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x+0.5f, y+0.5f, z);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x-0.5f, y+0.5f, z);
	
			GL11.glTexCoord2f(bx, by+TEX32);
			GL11.glVertex3f(x+0.5f, y+0.5f, z-zoff);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x-0.5f, y+0.5f, z-zoff);
		GL11.glEnd();

		// Higher Step Side
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x+0.5f, y+0.5f, z);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x-0.5f, y+0.5f, z);
	
			GL11.glTexCoord2f(bx,by+TEX64);
			GL11.glVertex3f(x+0.5f, y, z);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x-0.5f, y, z);
		GL11.glEnd();
	}
	
	/**
	 * Renders the side of a stair piece that runs North/South.  Verticies are in the following order:
	 * <pre>
	 *         6---5
	 *         |   |
	 *     2---4   |
	 *     |       |
	 *     1-------3
	 * </pre>
	 * 
	 * Note that the function is "NorthSouth" which corresponds to the stair direction;
	 * this will actually draw the face on the east or west sides.
	 * 
	 * @param t
	 * @param x
	 * @param y
	 * @param z
	 */
	public void renderStairSideNorthSouth(int t, float x, float y, float z, boolean swapX) {
		
		float bx = precalcSpriteSheetToTextureX[t];
		float by = precalcSpriteSheetToTextureY[t];
		
		float xoff=0.5f;
		if (swapX)
		{
			xoff = -0.5f;
		}
		
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
		
			GL11.glTexCoord2f(bx, by+TEX32);
			GL11.glVertex3f(x+xoff, y-0.5f, z-0.5f);
	
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x+xoff, y, z-0.5f);
			
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x-xoff, y-0.5f, z-0.5f);
	
			GL11.glTexCoord2f(bx+TEX32, by+TEX64);
			GL11.glVertex3f(x, y, z-0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x-xoff, y+0.5f, z-0.5f);
			
			GL11.glTexCoord2f(bx+TEX32, by);
			GL11.glVertex3f(x, y+0.5f, z-0.5f);

		GL11.glEnd();
	}	

	/**
	 * Renders the stair surface, for a stair running North/South
	 * 
	 * @param t Texture to draw
	 * @param x
	 * @param y
	 * @param z
	 * @param swapX
	 */
	public void renderStairSurfaceNorthSouth(int t, float x, float y, float z, boolean swapX) {
		
		float bx = precalcSpriteSheetToTextureX[t];
		float by = precalcSpriteSheetToTextureY[t];
		
		float xoff = 0.5f;
		if (swapX)
		{
			xoff = -0.5f;
		}
		
		// Lower Step surface
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x+xoff, y, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x+xoff, y, z-0.5f);
	
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x, y, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x, y, z-0.5f);
		GL11.glEnd();

		// Lower Step Side
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x+xoff, y, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x+xoff, y, z-0.5f);
	
			GL11.glTexCoord2f(bx,by+TEX32);
			GL11.glVertex3f(x+xoff, y-0.5f, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x+xoff, y-0.5f, z-0.5f);
		GL11.glEnd();

		// Higher Step surface
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by+TEX64);
			GL11.glVertex3f(x, y+0.5f, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x, y+0.5f, z-0.5f);
	
			GL11.glTexCoord2f(bx, by+TEX32);
			GL11.glVertex3f(x-xoff, y+0.5f, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX32);
			GL11.glVertex3f(x-xoff, y+0.5f, z-0.5f);
		GL11.glEnd();


		// Higher Step Side
		GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
			GL11.glTexCoord2f(bx, by);
			GL11.glVertex3f(x, y+0.5f, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by);
			GL11.glVertex3f(x, y+0.5f, z-0.5f);
	
			GL11.glTexCoord2f(bx,by+TEX64);
			GL11.glVertex3f(x, y, z+0.5f);
	
			GL11.glTexCoord2f(bx+TEX16, by+TEX64);
			GL11.glVertex3f(x, y, z-0.5f);
		GL11.glEnd();
	}
	
	/**
	 * Gets the block ID at the specified coordinate in the chunk.  This is
	 * only really used in the getAdj*BlockId() methods.
	 */
	public short getBlock(int x, int y, int z) {
		return blockData.value[y + (z * 128) + (x * 128 * 16)];
	}

	/**
	 * Gets the block data at the specified coordinates.
	 */
	public byte getData(int x, int y, int z) {
		int offset = y + (z * 128) + (x * 128 * 16);
		int halfOffset = offset / 2;
		if(offset % 2 == 0) {
			return (byte) (mapData.value[halfOffset] & 0xF);
		} else {
			// We shouldn't have to &0xF here, but if we don't the value
			// returned could be negative, even though that would be silly.
			return (byte) ((mapData.value[halfOffset] >> 4) & 0xF);
		}
	}
	
	/**
	 * Renders a "special" block; AKA something that's not just an ordinary cube.
	 * Basically it draws four "faces" of the object, which creates a plus sign of
	 * sorts.  This should probably be handled in some other way, actually.
	 * 
	 * @param bx Texture Beginning-X coordinate (inside the texture PNG)
	 * @param by Texture Beginning-Y coordinate
	 * @param ex Texture Ending-X coordinate
	 * @param ey Texture Ending-Y coordinate
	 * @param x Absolute X position of block
	 * @param y Absolute Y position of block
	 * @param z Absolute Z position of block
	 */
	public void renderSpecial(float bx, float by, float ex, float ey, float x, float y, float z)
	{
		 
		// GL11.glDisable(GL11.GL_CULL_FACE);
		 //GL11.glDisable(GL11.GL_DEPTH_TEST);
		 GL11.glBegin(GL11.GL_QUADS);
		 GL11.glNormal3f(1.0f, 0.0f, 0.0f);
		 GL11.glTexCoord2f(bx, by); 	GL11.glVertex3f(x+9/16.0f+TEX64, y+1.0f, 	z);
		 GL11.glTexCoord2f(ex, by); 	GL11.glVertex3f(x+9/16.0f+TEX64, y+1.0f, 	z+1.0f);
		 GL11.glTexCoord2f(ex, ey); 	GL11.glVertex3f(x+9/16.0f-TEX64, y, 		z+1.0f);
		 GL11.glTexCoord2f(bx, ey); 	GL11.glVertex3f(x+9/16.0f-TEX64, y,	 		z);
		
		 GL11.glNormal3f(-1.0f, 0.0f, 0.0f);
		 GL11.glTexCoord2f(bx, by); 	GL11.glVertex3f(x+7/16.0f+TEX64, y+1.0f,	z+1.0f);
		 GL11.glTexCoord2f(ex, by); 	GL11.glVertex3f(x+7/16.0f+TEX64, y+1.0f,	z);
		 GL11.glTexCoord2f(ex, ey); 	GL11.glVertex3f(x+7/16.0f-TEX64, y,			z);
		 GL11.glTexCoord2f(bx, ey); 	GL11.glVertex3f(x+7/16.0f-TEX64, y,			z+1.0f);
		 
		 GL11.glNormal3f(0.0f, 0.0f, 1.0f);
		 GL11.glTexCoord2f(bx, by); 	GL11.glVertex3f(x+1.0f,	y+1.0f,	z+9/16.0f+TEX64);
		 GL11.glTexCoord2f(ex, by); 	GL11.glVertex3f(x, 		y+1.0f,	z+9/16.0f+TEX64);
		 GL11.glTexCoord2f(ex, ey); 	GL11.glVertex3f(x, 		y, 		z+9/16.0f-TEX64);
		 GL11.glTexCoord2f(bx, ey);	 	GL11.glVertex3f(x+1.0f,	y, 		z+9/16.0f-TEX64);
		 
		 GL11.glNormal3f(0.0f, 0.0f, -1.0f);
		 GL11.glTexCoord2f(bx, by); 	GL11.glVertex3f(x, 		y+1.0f,	z+7/16.0f+TEX64);
		 GL11.glTexCoord2f(ex, by); 	GL11.glVertex3f(x+1.0f,	y+1.0f,	z+7/16.0f+TEX64);
		 GL11.glTexCoord2f(ex, ey); 	GL11.glVertex3f(x+1.0f,	y, 		z+7/16.0f-TEX64);
		 GL11.glTexCoord2f(bx, ey); 	GL11.glVertex3f(x, 		y, 		z+7/16.0f-TEX64);
		 
		 GL11.glEnd();
		 //GL11.glEnable(GL11.GL_DEPTH_TEST);
		 //GL11.glEnable(GL11.GL_CULL_FACE);	
	}
	
	/**
	 * Renders a torch, making an attempt to render properly given the wall face it's
	 * attached to, etc.  We take in textureId because we support redstone torches as
	 * well.
	 *
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderTorch(int textureId, int xxx, int yyy, int zzz) {
		 byte data = getData(xxx, yyy, zzz);
		 data &= 0xF;
		 switch (data) {
			 case 1:
				 renderRectDecoration(textureId, xxx, yyy, zzz, -30, 0f, 1f, -.6f, 0f);
				 return;
			 case 2:
				 renderRectDecoration(textureId, xxx, yyy, zzz, 30, 0f, 1f, .6f, 0f);
				 return;
			 case 3:
				 renderRectDecoration(textureId, xxx, yyy, zzz, 30, 1f, 0f, 0f, -.6f);
				 return;
			 case 4:
				 renderRectDecoration(textureId, xxx, yyy, zzz, -30, 1f, 0f, 0f, .6f);
				 return;
			 default:
				 renderRectDecoration(textureId, xxx, yyy, zzz);
				 return;
		 }
	}
	
	/**
	 * Renders a lever; copied and modified from renderTorch for the most part.
	 * TODO: Looks no better than the torches; should put the cobble base on at the least.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderLever(int textureId, int xxx, int yyy, int zzz)
	{
		byte data = getData(xxx, yyy, zzz);
		boolean thrown = false;
		if ((data & 0x8) == 0x8)
		{
			thrown = true;
		}
		data &= 7;
		//System.out.println("Data: " + data);
		 
		// First draw the cobblestoney box
		int cobble_tex = BLOCK_COBBLESTONE.tex_idx;
		float box_height = .15f;
		float box_length = .2f;
		float box_width = .15f;
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		switch (data) {
			case 1:
				renderVertical(cobble_tex, x-.5f, z+box_width, x-.5f+box_height, z+box_width, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x-.5f, z-box_width, x-.5f+box_height, z-box_width, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x-.5f+box_height, z+box_width, x-.5f+box_height, z-box_width, y-box_length, box_length*2f);
				renderHorizontal(cobble_tex, x-.5f, z-box_width, x-.5f+box_height, z+box_width, y-box_length);
				renderHorizontal(cobble_tex, x-.5f, z-box_width, x-.5f+box_height, z+box_width, y+box_length);
				break;
			case 2:
				renderVertical(cobble_tex, x+.5f, z+box_width, x+.5f-box_height, z+box_width, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x+.5f, z-box_width, x+.5f-box_height, z-box_width, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x+.5f-box_height, z+box_width, x+.5f-box_height, z-box_width, y-box_length, box_length*2f);
				renderHorizontal(cobble_tex, x+.5f, z-box_width, x+.5f-box_height, z+box_width, y-box_length);
				renderHorizontal(cobble_tex, x+.5f, z-box_width, x+.5f-box_height, z+box_width, y+box_length);
				break;
			case 3:
				renderVertical(cobble_tex, x-box_width, z-.5f, x-box_width, z-.5f+box_height, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x+box_width, z-.5f, x+box_width, z-.5f+box_height, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x-box_width, z-.5f+box_height, x+box_width, z-.5f+box_height, y-box_length, box_length*2f);
				renderHorizontal(cobble_tex, x-box_width, z-.5f, x+box_width, z-.5f+box_height, y-box_length);
				renderHorizontal(cobble_tex, x-box_width, z-.5f, x+box_width, z-.5f+box_height, y+box_length);
				break;
			case 4:
				renderVertical(cobble_tex, x-box_width, z+.5f, x-box_width, z+.5f-box_height, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x+box_width, z+.5f, x+box_width, z+.5f-box_height, y-box_length, box_length*2f);
				renderVertical(cobble_tex, x-box_width, z+.5f-box_height, x+box_width, z+.5f-box_height, y-box_length, box_length*2f);
				renderHorizontal(cobble_tex, x-box_width, z+.5f, x+box_width, z+.5f-box_height, y-box_length);
				renderHorizontal(cobble_tex, x-box_width, z+.5f, x+box_width, z+.5f-box_height, y+box_length);
				break;
			case 5:
				renderVertical(cobble_tex, x-box_width, z+box_length, x-box_width, z-box_length, y-.5f, box_height);
				renderVertical(cobble_tex, x+box_width, z+box_length, x+box_width, z-box_length, y-.5f, box_height);
				renderVertical(cobble_tex, x-box_width, z+box_length, x+box_width, z+box_length, y-.5f, box_height);
				renderVertical(cobble_tex, x+box_width, z-box_length, x-box_width, z-box_length, y-.5f, box_height);
				renderHorizontal(cobble_tex, x-box_width, z-box_length, x+box_width, z+box_length, y-.5f+box_height);
				break;
			case 6:
			default:
				renderVertical(cobble_tex, x-box_length, z+box_width, x-box_length, z-box_width, y-.5f, box_height);
				renderVertical(cobble_tex, x+box_length, z+box_width, x+box_length, z-box_width, y-.5f, box_height);
				renderVertical(cobble_tex, x-box_length, z+box_width, x+box_length, z+box_width, y-.5f, box_height);
				renderVertical(cobble_tex, x+box_length, z-box_width, x-box_length, z-box_width, y-.5f, box_height);
				renderHorizontal(cobble_tex, x-box_length, z-box_width, x+box_length, z+box_width, y-.5f+box_height);
				break;
		}

		// Now draw the lever itself
		if (thrown)
		{
			switch (data) {
				case 1:
					renderRectDecoration(textureId, xxx, yyy+1, zzz, -135, 0f, 1f, .6f, 0f);
					break;
				case 2:
					renderRectDecoration(textureId, xxx, yyy+1, zzz, 135, 0f, 1f, -.6f, 0f);
					break;
				case 3:
					renderRectDecoration(textureId, xxx, yyy+1, zzz, 135, 1f, 0f, 0f, .6f);
					break;
				case 4:
					renderRectDecoration(textureId, xxx, yyy+1, zzz, -135, 1f, 0f, 0f, -.6f);
					break;
				case 5:
					renderRectDecoration(textureId, xxx, yyy, zzz, -45, 1f, 0f, 0f, 0f);
					break;
				case 6:
					renderRectDecoration(textureId, xxx, yyy, zzz, 45, 0f, 1f, 0f, 0f);
					break;
			}
		}
		else
		{
			switch (data) {
				case 1:
					renderRectDecoration(textureId, xxx, yyy, zzz, -45, 0f, 1f, -.6f, 0f);
					break;
				case 2:
					renderRectDecoration(textureId, xxx, yyy, zzz, 45, 0f, 1f, .6f, 0f);
					break;
				case 3:
					renderRectDecoration(textureId, xxx, yyy, zzz, 45, 1f, 0f, 0f, -.6f);
					break;
				case 4:
					renderRectDecoration(textureId, xxx, yyy, zzz, -45, 1f, 0f, 0f, .6f);
					break;
				case 5:
					renderRectDecoration(textureId, xxx, yyy, zzz, 45, 1f, 0f, 0f, 0f);
					break;
				case 6:
					renderRectDecoration(textureId, xxx, yyy, zzz, -45, 0f, 1f, 0f, 0f);
					break;
			}
		}
	}

	/**
	 * Renders a decoration which is supposed to be a "cross" in a single block.  There's
	 * some code duplication from renderRectDecoration in here, but not too much, hopefully.
	 * This will require an entry in XRay.decorationStats for the given textureId.
	 */
	public void renderCrossDecoration(int textureId, int xxx, int yyy, int zzz)
	{
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy - 0.5f;

		// We do the "% 256" here because our texture ID might be in the "highlighted"
		// range, for Explored highlighting.
		TextureDecorationStats stats = XRay.decorationStats.get(textureId % 256);
		if (stats == null)
		{
			return;
		}
		float tex_begin_x = precalcSpriteSheetToTextureX[textureId] + stats.getTexLeft();
		float tex_begin_y = precalcSpriteSheetToTextureY[textureId] + stats.getTexTop();
		float tex_width = stats.getTexWidth();
		float tex_height = stats.getTexHeight();

		float width = stats.getWidth();
		float width_h = width/2f;
		float height = stats.getHeight();
		float top_tex_height;

		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x-width_h, y+height, z-width_h,
				x+width_h, y, z+width_h);
		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x+width_h, y+height, z-width_h,
				x-width_h, y, z+width_h);
	}

	/**
	 * Renders an rectangular decoration which is just standing straight up.  This will require
	 * an entry in XRay.decorationStats for the given textureId.
	 *
	 * Currently only used for torches and levers, actually.
	 */
	public void renderRectDecoration(int textureId, int xxx, int yyy, int zzz)
	{
		renderRectDecoration(textureId, xxx, yyy, zzz, 0, 0f, 0f, 0f, 0f);
	}

	/**
	 * Renders a rectangular decoration.  This will require an entry in XRay.decorationStats for
	 * the given textureId.  Optionally pass in some parameters for rotation, currently used
	 * for torches and levers.
	 *
	 * @param textureId Texture to draw
	 * @param xxx Chunk X
	 * @param yyy Chunk Y
	 * @param zzz Chunk Z
	 * @param rotate_degrees Degrees to rotate, use zero for no rotation
	 * @param rotate_x Use 1.0f to rotate in the X direction (passed to glRotatef)
	 * @param rotate_z Use 1.0f to rotate in the X direction (passed to glRotatef)
	 * @param x_off X offset, so it's not just in the center
	 * @param z_off Z offset, so it's not just in the center
	 */
	public void renderRectDecoration(int textureId, int xxx, int yyy, int zzz,
			int rotate_degrees, float rotate_x, float rotate_z, float x_off, float z_off)
	{
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy - 0.5f;

		boolean do_rotate = false;
		float tx=0, ty=0, tz=0;
		if (rotate_degrees != 0)
		{
			tx = x;
			ty = y;
			tz = z;
			x = x_off;
			y = 0;
			z = z_off;
			do_rotate = true;
		}

		float my_x = xxx + this.x*16;
		float my_z = zzz + this.z*16;
		float my_y = yyy - 0.5f;
		// We do the "% 256" here because our texture ID might be in the "highlighted"
		// range, for Explored highlighting.
		TextureDecorationStats stats = XRay.decorationStats.get(textureId % 256);
		if (stats == null)
		{
			return;
		}

		float tex_begin_x = precalcSpriteSheetToTextureX[textureId] + stats.getTexLeft();
		float tex_begin_y = precalcSpriteSheetToTextureY[textureId] + stats.getTexTop();
		float tex_width = stats.getTexWidth();
		float tex_height = stats.getTexHeight();

		float width = stats.getWidth();
		float width_h = width/2f;
		float height = stats.getHeight();
		float top_tex_height;
		if (height > width)
		{
			top_tex_height = tex_width/2f;
		}
		else
		{
			top_tex_height = tex_height;
		}

		// Math is for suckers; let's let the video hardware take care of rotation
		// Relatedly, is this how I should be drawing *everything?*  Draw relative
		// to the origin for the actual verticies, and then translate?
		if (do_rotate)
		{
			GL11.glPushMatrix();
			GL11.glTranslatef(tx, ty, tz);
			GL11.glRotatef((float)rotate_degrees, rotate_x, 0f, rotate_z);
		}
		
		// First draw the borders
		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x-width_h, y+height, z-width_h,
				x+width_h, y, z-width_h);
		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x-width_h, y+height, z+width_h,
				x+width_h, y, z+width_h);
		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x+width_h, y+height, z-width_h,
				x+width_h, y, z+width_h);
		renderNonstandardVertical(tex_begin_x, tex_begin_y, tex_width, tex_height,
				x-width_h, y+height, z+width_h,
				x-width_h, y, z-width_h);

		// Now the top
		renderNonstandardHorizontal(tex_begin_x, tex_begin_y, tex_width, top_tex_height,
				x-width_h, z-width_h,
				x+width_h, z+width_h,
				y+height);

		if (do_rotate)
		{
			GL11.glPopMatrix();
		}
	}
	
	/**
	 * Renders crops.  We still take the fully-grown textureId in the function so that everything
	 * remains defined in MinecraftConstants
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderCrops(int textureId, int xxx, int yyy, int zzz) {
		 float x = xxx + this.x*16 -0.5f;
		 float z = zzz + this.z*16 -0.5f;
		 float y = yyy - 0.5f;
		 
		 float bx,by;
		 float ex,ey;

		 bx = precalcSpriteSheetToTextureX[textureId];
		 by = precalcSpriteSheetToTextureY[textureId];

		 // Adjust for crop size; fortunately the textures are all in the same row so it's easy.
		 byte data = getData(xxx, yyy, zzz);
		 bx -= TEX16 * (7-data);
		 
		 ex = bx + TEX16;
		 ey = by + TEX32;
		 
		 renderSpecial(bx, by, ex, ey, x, y, z);
	}
    
	/**
	 * Renders a ladder, given its attached-side data.  We still take in textureId just so
	 * that everything's still defined in MinecraftConstants
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderLadder(int textureId, int xxx, int yyy, int zzz) {
		 float x = xxx + this.x*16;
		 float z = zzz + this.z*16;
		 float y = yyy;
		 
		 byte data = getData(xxx, yyy, zzz);
		 switch(data)
		 {
		 	case 2:
		 		// East
		 		this.renderWestEast(textureId, x, y, z+1.0f-TEX64);
		 		break;
		 	case 3:
		 		// West
		 		this.renderWestEast(textureId, x, y, z+TEX64);
		 		break;
		 	case 4:
		 		// North
		 		this.renderNorthSouth(textureId, x+1.0f-TEX64, y, z);
		 		break;
		 	case 5:
	 		default:
	 			// South
				this.renderNorthSouth(textureId, x+TEX64, y, z);
	 			break;
		 }
	}
    
	/**
	 * Renders a vine, given its attached-side data.  Pretty much identical
	 * to renderLadder, except that there's different data values.  Alas!
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderVine(int textureId, int xxx, int yyy, int zzz, int blockOffset) {
		 float x = xxx + this.x*16;
		 float z = zzz + this.z*16;
		 float y = yyy;
		 
		 byte data = getData(xxx, yyy, zzz);
		 boolean rendered = false;
		 if ((data & 1) == 1)
		 {
			// West
			this.renderWestEast(textureId, x, y, z+1.0f-TEX64);
			rendered = true;
		 }
		 if ((data & 2) == 2)
		 {
			// North
			this.renderNorthSouth(textureId, x+TEX64, y, z);
			rendered = true;
		 }
		 if ((data & 4) == 4)
		 {
			// East
			this.renderWestEast(textureId, x, y, z+TEX64);
			rendered = true;
		 }
		 if ((data & 8) == 8)
		 {
			// South
			this.renderNorthSouth(textureId, x+1.0f-TEX64, y, z);
			rendered = true;
		 }
		 if (data == 0 || (rendered && yyy < 127 && isSolid(blockData.value[blockOffset+1])))
		 {
			// Top
			this.renderHorizontal(textureId, x-.5f, z-.5f, x+.5f, z+.5f, y+.45f);
		 }
	}

	/**
	 * This is actually used for rendering "decoration" type things which are on
	 * the floor (eg: minecart tracks, redstone wires, etc)
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderFloor(int textureId, int xxx, int yyy, int zzz) {
		 float x = xxx + this.x*16;
		 float z = zzz + this.z*16;
		 float y = yyy;
		 
		this.renderTopDown(textureId, x, y+TEX64, z);
	}

	/**
	 * Minecart tracks
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderMinecartTracks(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		 
		byte data = getData(xxx, yyy, zzz);
		if (data > 0x5)
		{
			textureId -= 16;
		}
 
		switch (data)
		{
			case 0x0:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 1);
				break;
			case 0x1:
				this.renderTopDown(textureId, x, y+TEX64, z);
				break;
			case 0x2:
				this.renderArbitraryRect(textureId,
						x-0.5f, y-0.5f, z+0.5f,
						x-0.5f, y-0.5f, z-0.5f,
						x+0.5f, y+0.5f, z+0.5f,
						x+0.5f, y+0.5f, z-0.5f
						);
				break;
			case 0x3:
				this.renderArbitraryRect(textureId,
						x-0.5f, y+0.5f, z+0.5f,
						x-0.5f, y+0.5f, z-0.5f,
						x+0.5f, y-0.5f, z+0.5f,
						x+0.5f, y-0.5f, z-0.5f
						);
				break;
			case 0x4:
				this.renderArbitraryRect(textureId,
						x-0.5f, y+0.5f, z-0.5f,
						x+0.5f, y+0.5f, z-0.5f,
						x-0.5f, y-0.5f, z+0.5f,
						x+0.5f, y-0.5f, z+0.5f
						);
				break;
			case 0x5:
				this.renderArbitraryRect(textureId,
						x-0.5f, y-0.5f, z-0.5f,
						x+0.5f, y-0.5f, z-0.5f,
						x-0.5f, y+0.5f, z+0.5f,
						x+0.5f, y+0.5f, z+0.5f
						);
				break;
			case 0x6:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 3);
				break;
			case 0x7:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 2);
				break;
			case 0x8:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 1);
				break;
			case 0x9:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 0);
				break;
			default:
				// Just do the usual for now
				this.renderTopDown(textureId, x, y+TEX64, z);
				break;
		}
	}

	/**
	 * "Simple" rails, which don't have corners.  This actually isn't as
	 * simple as it should be, since we have a special-case for powered rails.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderSimpleRail(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		 
		byte data = getData(xxx, yyy, zzz);
		byte powered = data;
		powered >>= 3;
		if (powered > 0)
		{
			// This is just for powered rails, to light them up properly
			textureId += 16;
		}
		data &= 7;
 
		switch (data)
		{
			case 0x0:
				this.renderTopDownRotate(textureId, x, y+TEX64, z, 1);
				break;
			case 0x1:
				this.renderTopDown(textureId, x, y+TEX64, z);
				break;
			case 0x2:
				this.renderArbitraryRect(textureId,
						x-0.5f, y-0.5f, z+0.5f,
						x-0.5f, y-0.5f, z-0.5f,
						x+0.5f, y+0.5f, z+0.5f,
						x+0.5f, y+0.5f, z-0.5f
						);
				break;
			case 0x3:
				this.renderArbitraryRect(textureId,
						x-0.5f, y+0.5f, z+0.5f,
						x-0.5f, y+0.5f, z-0.5f,
						x+0.5f, y-0.5f, z+0.5f,
						x+0.5f, y-0.5f, z-0.5f
						);
				break;
			case 0x4:
				this.renderArbitraryRect(textureId,
						x-0.5f, y+0.5f, z-0.5f,
						x+0.5f, y+0.5f, z-0.5f,
						x-0.5f, y-0.5f, z+0.5f,
						x+0.5f, y-0.5f, z+0.5f
						);
				break;
			case 0x5:
				this.renderArbitraryRect(textureId,
						x-0.5f, y-0.5f, z-0.5f,
						x+0.5f, y-0.5f, z-0.5f,
						x-0.5f, y+0.5f, z+0.5f,
						x+0.5f, y+0.5f, z+0.5f
						);
				break;
			default:
				// Just do the usual for now
				this.renderTopDown(textureId, x, y+TEX64, z);
				break;
		}
	}
	
	/**
	 * Renders a pressure plate.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderPlate(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		float radius = 0.4f;
		
		// The plate itself
		this.renderHorizontal(textureId, x+radius, z+radius, x-radius, z-radius, y-0.45f);
		
		// Sides
		this.renderVertical(textureId, x+radius, z+radius, x+radius, z-radius, y-0.5f, 0.05f);
		this.renderVertical(textureId, x-radius, z+radius, x-radius, z-radius, y-0.5f, 0.05f);
		this.renderVertical(textureId, x+radius, z+radius, x-radius, z+radius, y-0.5f, 0.05f);
		this.renderVertical(textureId, x+radius, z-radius, x-radius, z-radius, y-0.5f, 0.05f);
	}

	/**
	 * Renders a thin slice of something on the ground (used for snow currently).  Practically
	 * the same as renderPlate actually, just wider and a bit taller
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderThinslice(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		float radius = 0.48f;
		
		// The top face
		this.renderHorizontal(textureId, x+radius, z+radius, x-radius, z-radius, y-0.38f);
		
		// Sides
		this.renderVertical(textureId, x+radius, z+radius, x+radius, z-radius, y-0.48f, 0.1f);
		this.renderVertical(textureId, x-radius, z+radius, x-radius, z-radius, y-0.48f, 0.1f);
		this.renderVertical(textureId, x+radius, z+radius, x-radius, z+radius, y-0.48f, 0.1f);
		this.renderVertical(textureId, x+radius, z-radius, x-radius, z-radius, y-0.48f, 0.1f);
	}

	/**
	 * Renders a bed block.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderBed(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		float side_part = 0.49f;
		float side_full = 0.5f;
		float bed_height = 0.5625f;
		float horiz_off = bed_height-0.5f;
		float bed_tex_height = TEX256/2f*9f;
		boolean head = true;

		byte data = getData(xxx, yyy, zzz);
		data &= 0xF;
		if ((data & 0x8) == 0)
		{
			textureId -= 1;
			head = false;
		}
		data &= 0x3;

		float side_tex_x = precalcSpriteSheetToTextureX[textureId+16];
		float side_tex_y = precalcSpriteSheetToTextureY[textureId+32]-bed_tex_height;

		// Use GL to rotate these properly
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);

		// We're drawing the bed with the head facing East (direction 2)
		if (data == 0)
		{
			// Pointing West
			GL11.glRotatef(180f, 0f, 1f, 0f);
		}
		else if (data == 1)
		{
			// Pointing North
			GL11.glRotatef(90f, 0f, 1f, 0f);
		}
		else if (data == 3)
		{
			// Pointing South
			GL11.glRotatef(-90f, 0f, 1f, 0f);
		}

		float end_tex_x, end_tex_y;
		float first_z, second_z, end_z;
		if (head)
		{
			end_tex_x = precalcSpriteSheetToTextureX[textureId+17];
			end_tex_y = precalcSpriteSheetToTextureY[textureId+33]-bed_tex_height;
			first_z = side_full;
			second_z = -side_part;
			end_z = -side_part;
		}
		else
		{
			end_tex_x = precalcSpriteSheetToTextureX[textureId+15];
			end_tex_y = precalcSpriteSheetToTextureY[textureId+31]-bed_tex_height;
			first_z = side_part;
			second_z = -side_full;
			end_z = side_part;
		}

		// Top face
		this.renderHorizontal(textureId, side_part, first_z, -side_part, second_z, horiz_off);

		// Side faces
		this.renderNonstandardVertical(side_tex_x, side_tex_y, TEX16, bed_tex_height, side_part, bed_height-side_full, first_z, side_part, -side_full, second_z);
		this.renderNonstandardVertical(side_tex_x, side_tex_y, TEX16, bed_tex_height, -side_part, bed_height-side_full, first_z, -side_part, -side_full, second_z);

		// end face (either the very foot or the very head of the bed)
		this.renderNonstandardVertical(end_tex_x, end_tex_y, TEX16, bed_tex_height, side_part, bed_height-side_full, end_z, -side_part, -side_full, end_z);

		// Pop the matrix
		GL11.glPopMatrix();
	}
	
	/**
	 * Renders a door
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderDoor(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		
		byte data = getData(xxx, yyy, zzz);
		if ((data & 0x8) == 0x8)
		{
			textureId -= 16;
		}
		boolean swung = false;
		if ((data & 0x4) == 0x4)
		{
			swung = true;
		}
		int dir = (data & 0x3);

		// TODO: need to fix texture orientation
		if ((dir == 3 && swung) || (dir == 0 && !swung))
		{
			// North			
			this.renderNorthSouth(textureId, x, y, z);
		}
		else if ((dir == 0 && swung) || (dir == 1 && !swung))
		{
			// East
			this.renderWestEast(textureId, x, y, z);
		}
		else if ((dir == 1 && swung) || (dir == 2 && !swung))
		{
			// South
			this.renderNorthSouth(textureId, x+1, y, z);
		}
		else
		{
			// West
			this.renderWestEast(textureId, x, y, z+1);
		}
		
	}
	
	/**
	 * Renders a trapdoor
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderTrapdoor(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		float twidth = .1f;
		//float twidth_h = twidth/2f;
		float toff = .02f;
		
		byte data = getData(xxx, yyy, zzz);
		boolean swung = false;
		if ((data & 0x4) == 0x4)
		{
			swung = true;
		}
		int dir = (data & 0x3);

		float tex_x = precalcSpriteSheetToTextureX[textureId];
		float tex_y = precalcSpriteSheetToTextureY[textureId];
		float tex_dx = TEX16;
		float tex_dy = TEX32 * twidth;

		// Use GL to rotate these properly
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);
		if (swung)
		{
			if (dir == 0)
			{
				// West
				GL11.glRotatef(-90f, 1f, 0f, 0f);
			}
			else if (dir == 1)
			{
				// East
				GL11.glRotatef(90f, 1f, 0f, 0f);
			}
			else if (dir == 2)
			{
				// South
				GL11.glRotatef(90f, 0f, 0f, 1f);
			}
			else
			{
				// North
				GL11.glRotatef(-90f, 0f, 0f, 1f);
			}
		}
		
		// First the faces
		//this.renderHorizontal(textureId, .5f-toff, .5f-toff, -.5f+toff, -.5f+toff, -.5f+toff);
		this.renderHorizontal(textureId, .5f-toff, .5f-toff, -.5f+toff, -.5f+toff, -.5f+toff+twidth);

		// Now the sides
		this.renderNonstandardVertical(tex_x, tex_y, tex_dx, tex_dy,
				.5f-toff, -.5f+toff,         .5f-toff,
				-.5f+toff, -.5f+toff+twidth, .5f-toff);
		this.renderNonstandardVertical(tex_x, tex_y, tex_dx, tex_dy,
				.5f-toff, -.5f+toff,        .5f-toff,
				.5f-toff, -.5f+toff+twidth, -.5f+toff);
		this.renderNonstandardVertical(tex_x, tex_y, tex_dx, tex_dy,
				-.5f+toff, -.5f+toff,        -.5f+toff,
				-.5f+toff, -.5f+toff+twidth, .5f-toff);
		this.renderNonstandardVertical(tex_x, tex_y, tex_dx, tex_dy,
				-.5f+toff, -.5f+toff,       -.5f+toff,
				.5f-toff, -.5f+toff+twidth, -.5f+toff);

		GL11.glPopMatrix();
	}
	
	/**
	 * Renders stair graphics
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderStairs(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		
		byte data = getData(xxx, yyy, zzz);
		boolean swap = false;
		if (data == 0 || data == 2)
		{
			swap = true;
		}

		if (data == 0 || data == 1)
		{
			// 0 is ascending-south, 1 is ascending-north
			
			// Sides
			this.renderStairSideNorthSouth(textureId, x, y, z+.05f, swap);
			this.renderStairSideNorthSouth(textureId, x, y, z+.95f, swap);
			
			// Back
			if (swap)
			{
				this.renderNorthSouth(textureId, x+0.94f, y, z, 0.5f, 0.45f);
			}
			else
			{
				this.renderNorthSouth(textureId, x+0.06f, y, z, 0.5f, 0.45f);
			}
			
			// Bottom
			this.renderTopDown(textureId, x, y, z, 0.45f);
			
			// Stair Surface
			this.renderStairSurfaceNorthSouth(textureId, x, y, z, swap);
		}
		else
		{
			// 2 is ascending-west, 3 is ascending-east
			
			// Sides
			this.renderStairSideWestEast(textureId, x+.05f, y, z, swap);
			this.renderStairSideWestEast(textureId, x+.95f, y, z, swap);
			
			// Back
			if (swap)
			{
				this.renderWestEast(textureId, x, y, z+0.94f, 0.5f, 0.45f);
			}
			else
			{
				this.renderWestEast(textureId, x, y, z+0.06f, 0.5f, 0.45f);
			}
			
			// Bottom
			this.renderTopDown(textureId, x, y, z, 0.45f);
			
			// Stair Surface
			this.renderStairSurfaceWestEast(textureId, x, y, z, swap);		
		}
		
	}
	
	/**
	 * Renders a signpost.
	 * TODO: show the actual message
	 * TODO: should be solid instead of just one plane
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderSignpost(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		
		float signBottom = 0f;
		float signHeight = .6f;
		float postRadius = .05f;
		float face_spacing = 3; // in degrees
		
		// First a signpost
		this.renderVertical(textureId, x-postRadius, z-postRadius, x+postRadius, z-postRadius, y-0.5f, 0.5f+signBottom);
		this.renderVertical(textureId, x-postRadius, z+postRadius, x+postRadius, z+postRadius, y-0.5f, 0.5f+signBottom);
		this.renderVertical(textureId, x+postRadius, z-postRadius, x+postRadius, z+postRadius, y-0.5f, 0.5f+signBottom);
		this.renderVertical(textureId, x-postRadius, z+postRadius, x-postRadius, z-postRadius, y-0.5f, 0.5f+signBottom);
		
		// Signpost top
		this.renderHorizontal(textureId, x-postRadius, z-postRadius, x+postRadius, z+postRadius, y+signBottom);
		
		// Now we continue to draw the sign itself.
		byte data = getData(xxx, yyy, zzz);
		data &= 0xF;
		// data: 0 is West, increasing numbers add 22.5 degrees (so 4 is North, 8 south, etc)
		// Because we're not actually drawing the message (yet), as far as we're concerned
		// West is the same as East, etc.
		float angle = (data % 8) * 22.5f;
		float radius = 0.5f;

		angle -= face_spacing;
		// First x/z
		float x1a = x + radius * (float)Math.cos(Math.toRadians(angle));
		float z1a = z + radius * (float)Math.sin(Math.toRadians(angle));
		angle += face_spacing*2;
		float x1b = x + radius * (float)Math.cos(Math.toRadians(angle));
		float z1b = z + radius * (float)Math.sin(Math.toRadians(angle));
		
		// Now the other side
		angle += 180;
		float x2a = x + radius * (float)Math.cos(Math.toRadians(angle));
		float z2a = z + radius * (float)Math.sin(Math.toRadians(angle));
		angle -= face_spacing*2;
		float x2b = x + radius * (float)Math.cos(Math.toRadians(angle));
		float z2b = z + radius * (float)Math.sin(Math.toRadians(angle));
		
		// Faces
		this.renderVertical(textureId, x1a, z1a, x2a, z2a, y+signBottom, signHeight);
		this.renderVertical(textureId, x1b, z1b, x2b, z2b, y+signBottom, signHeight);
		
		// Sides
		this.renderVertical(textureId, x1a, z1a, x1b, z1b, y+signBottom, signHeight);
		this.renderVertical(textureId, x2a, z2a, x2b, z2b, y+signBottom, signHeight);
		
		// Top/Bottom
		this.renderHorizontalAskew(textureId, x1a, z1a, x1b, z1b, x2a, z2a, x2b, z2b, y+signBottom);
		this.renderHorizontalAskew(textureId, x1a, z1a, x1b, z1b, x2a, z2a, x2b, z2b, y+signBottom+signHeight);
	}
	
	/**
	 * Renders a wall sign.  This is virtually identical to renderLadder, except that
	 * we draw a smaller box, basically.
	 * TODO: Would be kind of neat to actually draw the message, too.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderWallSign(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;

		float faceX1, faceX2;
		float faceZ1, faceZ2;
 		float back_dX, back_dZ;
 		float sign_length = 0.4f;
		
		byte data = getData(xxx, yyy, zzz);
		switch(data)
		{
		 	case 2:
		 		// East
	 			faceX1 = x-sign_length;
	 			faceX2 = x+sign_length;
	 			faceZ1 = z+0.45f;
	 			faceZ2 = z+0.45f;
	 			back_dX = 0f;
	 			back_dZ = 0.05f;
		 		break;
		 	case 3:
		 		// West
	 			faceX1 = x-sign_length;
	 			faceX2 = x+sign_length;
	 			faceZ1 = z-0.45f;
	 			faceZ2 = z-0.45f;
	 			back_dX = 0f;
	 			back_dZ = -0.05f;
		 		break;
		 	case 4:
		 		// North
	 			faceX1 = x+0.45f;
	 			faceX2 = x+0.45f;
	 			faceZ1 = z-sign_length;
	 			faceZ2 = z+sign_length;
	 			back_dX = 0.05f;
	 			back_dZ = 0f;
		 		break;
		 	case 5:
	 		default:
	 			// South
	 			faceX1 = x-0.45f;
	 			faceX2 = x-0.45f;
	 			faceZ1 = z-sign_length;
	 			faceZ2 = z+sign_length;
	 			back_dX = -0.05f;
	 			back_dZ = 0f;
	 			break;
		}
		
		// Face
		this.renderVertical(textureId, faceX1, faceZ1, faceX2, faceZ2, y-0.2f, 0.5f);
		
		// Sides
		this.renderVertical(textureId, faceX1, faceZ1, faceX1+back_dX, faceZ1+back_dZ, y-0.2f, 0.5f);
		this.renderVertical(textureId, faceX2, faceZ2, faceX2+back_dX, faceZ2+back_dZ, y-0.2f, 0.5f);
		
		// Top/Bottom
		this.renderHorizontal(textureId, faceX1, faceZ1, faceX2+back_dX, faceZ2+back_dZ, y-0.2f);
		this.renderHorizontal(textureId, faceX1, faceZ1, faceX2+back_dX, faceZ2+back_dZ, y+0.3f);
	}
	
	/**
	 * Renders a fence.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 * @param blockOffset Should be passed in from our main draw loop so we don't have to recalculate
	 */
	public void renderFence(int textureId, int xxx, int yyy, int zzz, int blockOffset) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		float slat_start = y+fence_slat_start_offset;
		
		// First the fencepost
		this.renderVertical(textureId, x+fence_postsize, z+fence_postsize, x+fence_postsize, z-fence_postsize, y-0.5f, 1f, 4, 16, 6, 0);
		this.renderVertical(textureId, x+fence_postsize, z-fence_postsize, x-fence_postsize, z-fence_postsize, y-0.5f, 1f, 4, 16, 6, 0);
		this.renderVertical(textureId, x-fence_postsize, z-fence_postsize, x-fence_postsize, z+fence_postsize, y-0.5f, 1f, 4, 16, 6, 0);
		this.renderVertical(textureId, x-fence_postsize, z+fence_postsize, x+fence_postsize, z+fence_postsize, y-0.5f, 1f, 4, 16, 6, 0);
		this.renderHorizontal(textureId, x+fence_postsize, z+fence_postsize, x-fence_postsize, z-fence_postsize, y+0.5f, 4, 4, 6, 6, false);

		// Check for adjacent fences in the -x direction
		if (this.getAdjNorthBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
		{
			// Bottom slat
			this.renderVertical(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z+fence_postsize_h, slat_start, fence_slat_height, 16, 3, 0, 5);
			this.renderVertical(textureId, x-fence_postsize, z-fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start, fence_slat_height, 16, 3, 0, 5);
			this.renderHorizontal(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start, 2, 16, 14, 0, false);
			this.renderHorizontal(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start+fence_slat_height, 2, 16, 14, 0, false);

			// Top slat
			this.renderVertical(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z+fence_postsize_h, slat_start+fence_top_slat_offset, fence_slat_height, 16, 3, 0, 5);
			this.renderVertical(textureId, x-fence_postsize, z-fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start+fence_top_slat_offset, fence_slat_height, 16, 3, 0, 5);
			this.renderHorizontal(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start+fence_top_slat_offset, 2, 16, 14, 0, false);
			this.renderHorizontal(textureId, x-fence_postsize, z+fence_postsize_h, x-1f+fence_postsize, z-fence_postsize_h, slat_start+fence_top_slat_offset+fence_slat_height, 2, 16, 14, 0, false);
		}
		
		// Check for adjacent fences in the -z direction
		if (this.getAdjEastBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
		{
			// Bottom slat
			this.renderVertical(textureId, x+fence_postsize_h, z-fence_postsize, x+fence_postsize_h, z-1f+fence_postsize, slat_start, fence_slat_height, 16, 3, 0, 5);
			this.renderVertical(textureId, x-fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start, fence_slat_height, 16, 3, 0, 5);
			this.renderHorizontal(textureId, x+fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start, 2, 16, 14, 0, true);
			this.renderHorizontal(textureId, x+fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start+fence_slat_height, 2, 16, 14, 0, true);

			// Top slat
			this.renderVertical(textureId, x+fence_postsize_h, z-fence_postsize, x+fence_postsize_h, z-1f+fence_postsize, slat_start+fence_top_slat_offset, fence_slat_height, 16, 3, 0, 5);
			this.renderVertical(textureId, x-fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start+fence_top_slat_offset, fence_slat_height, 16, 3, 0, 5);
			this.renderHorizontal(textureId, x+fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start+fence_top_slat_offset, 2, 16, 14, 0, true);
			this.renderHorizontal(textureId, x+fence_postsize_h, z-fence_postsize, x-fence_postsize_h, z-1f+fence_postsize, slat_start+fence_top_slat_offset+fence_slat_height, 2, 16, 14, 0, true);
		}
	}
	
	/**
	 * Renders a fence gate.  Good lord, this is a heck of a function.   I continually feel like I'm
	 * going about these things in the wrong way.  Ah, well - it works.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 * @param blockOffset Should be passed in from our main draw loop so we don't have to recalculate
	 */
	public void renderFenceGate(int textureId, int xxx, int yyy, int zzz, int blockOffset) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;

		float post_x1 = .375f;
		float post_x2 = .5f;
		float post_z = .0625f;
		float post_y_start = -.1875f;
		float post_h = .6875f;
		float middle_w = .125f;
		float middle_y = fence_slat_start_offset + fence_slat_height;
		float middle_h = fence_slat_start_offset + fence_top_slat_offset - middle_y;
		float middle_width = .0625f;

		byte data = getData(xxx, yyy, zzz);
		boolean open = ((data & 0x4) == 0x4);
		int dir = (data & 0x3);

		boolean have_fence_1 = false;
		boolean have_fence_2 = false;

		// GL stuff; only draw one way
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);
		switch (dir)
		{
			case 1:
				GL11.glRotatef(270f, 0f, 1f, 0f);
				if (getAdjWestBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_1 = true;
				}
				if (getAdjEastBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_2 = true;
				}
				break;
			case 2:
				GL11.glRotatef(180f, 0f, 1f, 0f);
				if (getAdjNorthBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_1 = true;
				}
				if (getAdjSouthBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_2 = true;
				}
				break;
			case 3:
				GL11.glRotatef(90f, 0f, 1f, 0f);
				if (getAdjEastBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_1 = true;
				}
				if (getAdjWestBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_2 = true;
				}
				break;
			case 0:
			default:
				if (getAdjSouthBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_1 = true;
				}
				if (getAdjNorthBlockId(xxx, yyy, zzz, blockOffset) == BLOCK_FENCE.id)
				{
					have_fence_2 = true;
				}
				break;
		}

		// One side post
		this.renderVertical(textureId, post_x1, post_z, post_x2, post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, post_x1, -post_z, post_x2, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, post_x1, post_z, post_x1, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, post_x2, post_z, post_x2, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderHorizontal(textureId, post_x1, post_z, post_x2, -post_z, post_y_start, 2, 2, 7, 7, false);
		this.renderHorizontal(textureId, post_x1, post_z, post_x2, -post_z, post_y_start+post_h, 2, 2, 7, 7, false);
		
		// ... and its connecting fence post, if it exists
		if (have_fence_1)
		{
			// Bottom
			this.renderVertical(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, post_z, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, post_x2, -post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset+fence_slat_height, 6, 2, 5, 7, true);

			// Top
			this.renderVertical(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, post_x2, -post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, post_x2, post_z, post_x2+.5f-fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset+fence_slat_height, 6, 2, 5, 7, true);
		}

		// The other side post
		this.renderVertical(textureId, -post_x1, post_z, -post_x2, post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, -post_x1, -post_z, -post_x2, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, -post_x1, post_z, -post_x1, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderVertical(textureId, -post_x2, post_z, -post_x2, -post_z, post_y_start, post_h, 2, 11, 7, 0);
		this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, -post_z, post_y_start, 2, 2, 7, 7, false);
		this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, -post_z, post_y_start+post_h, 2, 2, 7, 7, false);
		
		// ... and its connecting fence post, if it exists
		if (have_fence_2)
		{
			// Bottom
			this.renderVertical(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, post_z, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, -post_x2, -post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset+fence_slat_height, 6, 2, 5, 7, true);

			// Top
			this.renderVertical(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, -post_x2, -post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, -post_x2, post_z, -post_x2-.5f+fence_postsize, -post_z, fence_slat_start_offset+fence_top_slat_offset+fence_slat_height, 6, 2, 5, 7, true);
		}

		// Now the gate itself
		if (open)
		{
			// One side, bottom slat
			this.renderVertical(textureId, post_x1, post_z, post_x1, .5f, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, post_x2, post_z, post_x2, .5f, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, post_x1, post_z, post_x2, .5f, fence_slat_start_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, post_x1, post_z, post_x2, .5f, fence_slat_start_offset+fence_slat_height, 6, 2, 5, 7, false);

			// One side, top slat
			this.renderVertical(textureId, post_x1, post_z, post_x1, .5f, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, post_x2, post_z, post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, post_x1, post_z, post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, post_x1, post_z, post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset+fence_slat_height, 6, 2, 5, 7, false);

			// One side, middle bit
			this.renderVertical(textureId, post_x1, .5f, post_x1, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, post_x2, .5f, post_x2, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, post_x1, .5f-middle_w, post_x2, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, post_x1, .5f, post_x2, .5f, fence_slat_start_offset, fence_slat_height+fence_top_slat_offset, 2, 9, 7, 0);

			// Other side, bottom slat
			this.renderVertical(textureId, -post_x1, post_z, -post_x1, .5f, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, -post_x2, post_z, -post_x2, .5f, fence_slat_start_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, .5f, fence_slat_start_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, .5f, fence_slat_start_offset+fence_slat_height, 6, 2, 5, 7, false);

			// Other side, top slat
			this.renderVertical(textureId, -post_x1, post_z, -post_x1, .5f, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderVertical(textureId, -post_x2, post_z, -post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 6, 3, 5, 7);
			this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset, 6, 2, 5, 7, true);
			this.renderHorizontal(textureId, -post_x1, post_z, -post_x2, .5f, fence_slat_start_offset+fence_top_slat_offset+fence_slat_height, 6, 2, 5, 7, false);

			// Other side, middle bit
			this.renderVertical(textureId, -post_x1, .5f, -post_x1, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, -post_x2, .5f, -post_x2, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, -post_x1, .5f-middle_w, -post_x2, .5f-middle_w, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, -post_x1, .5f, -post_x2, .5f, fence_slat_start_offset, fence_slat_height+fence_top_slat_offset, 2, 9, 7, 0);
		}
		else
		{
			// Bottom bar
			this.renderVertical(textureId, post_x1, post_z, -post_x1, post_z, fence_slat_start_offset, fence_slat_height, 12, 3, 2, 7);
			this.renderVertical(textureId, post_x1, -post_z, -post_x1, -post_z, fence_slat_start_offset, fence_slat_height, 12, 3, 2, 7);
			this.renderHorizontal(textureId, post_x1, post_z, -post_x1, -post_z, fence_slat_start_offset, 12, 2, 2, 3, true);
			this.renderHorizontal(textureId, post_x1, post_z, -post_x1, -post_z, fence_slat_start_offset+fence_slat_height, 12, 2, 2, 3, true);

			// Top bar
			this.renderVertical(textureId, post_x1, post_z, -post_x1, post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 12, 3, 2, 7);
			this.renderVertical(textureId, post_x1, -post_z, -post_x1, -post_z, fence_slat_start_offset+fence_top_slat_offset, fence_slat_height, 12, 3, 2, 7);
			this.renderHorizontal(textureId, post_x1, post_z, -post_x1, -post_z, fence_slat_start_offset+fence_top_slat_offset, 12, 2, 2, 3, true);
			this.renderHorizontal(textureId, post_x1, post_z, -post_x1, -post_z, fence_slat_start_offset+fence_top_slat_offset+fence_slat_height, 12, 2, 2, 3, true);

			// Middle bit
			this.renderVertical(textureId, middle_w, post_z, -middle_w, post_z, middle_y, middle_h, 4, 3, 6, 5);
			this.renderVertical(textureId, middle_w, -post_z, -middle_w, -post_z, middle_y, middle_h, 4, 3, 6, 5);
			this.renderVertical(textureId, middle_w, middle_width, middle_w, -middle_width, middle_y, middle_h, 2, 3, 7, 5);
			this.renderVertical(textureId, -middle_w, middle_width, -middle_w, -middle_width, middle_y, middle_h, 2, 3, 7, 5);
		}

		// aaand pop our GL matrix
		GL11.glPopMatrix();
	}

	/**
	 * Renders a button.
	 */
	public void renderButton(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		
		float faceX1, faceX2;
		float faceZ1, faceZ2;
		float back_dX, back_dZ;
		float button_radius = .1f;
		
		byte data = getData(xxx, yyy, zzz);
		switch(data)
		{
		 	case 1:
	 			// South
		 		faceX1 = x-0.5f+button_radius;
		 		faceX2 = x-0.5f+button_radius;
		 		faceZ1 = z-button_radius;
		 		faceZ2 = z+button_radius;
		 		back_dX = -button_radius;
		 		back_dZ = 0;
	 			break;
		 	case 2:
		 		// North
		 		faceX1 = x+0.5f-button_radius;
		 		faceX2 = x+0.5f-button_radius;
		 		faceZ1 = z-button_radius;
		 		faceZ2 = z+button_radius;
		 		back_dX = button_radius;
		 		back_dZ = 0;
		 		break;
		 	case 3:
		 		// West
		 		faceX1 = x-button_radius;
		 		faceX2 = x+button_radius;
		 		faceZ1 = z-0.5f+button_radius;
		 		faceZ2 = z-0.5f+button_radius;
		 		back_dX = 0;
		 		back_dZ = -button_radius;
		 		break;
		 	case 4:
	 		default:
		 		// East
		 		faceX1 = x-button_radius;
		 		faceX2 = x+button_radius;
		 		faceZ1 = z+0.5f-button_radius;
		 		faceZ2 = z+0.5f-button_radius;
		 		back_dX = 0;
		 		back_dZ = button_radius;
		 		break;
		}
		
		// Button face
		this.renderVertical(textureId, faceX1, faceZ1, faceX2, faceZ2, y-button_radius, button_radius*2);
		
		// Sides
		this.renderVertical(textureId, faceX1, faceZ1, faceX1+back_dX, faceZ1+back_dZ, y-button_radius, button_radius*2);
		this.renderVertical(textureId, faceX2, faceZ2, faceX2+back_dX, faceZ2+back_dZ, y-button_radius, button_radius*2);
		
		// Top/Bottom
		this.renderHorizontal(textureId, faceX1, faceZ1, faceX2+back_dX, faceZ2+back_dZ, y-button_radius);
		this.renderHorizontal(textureId, faceX1, faceZ1, faceX2+back_dX, faceZ2+back_dZ, y+button_radius);

	}
	
	/**
	 * Portal square rendering.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 * @param blockOffset Should be passed in from our main draw loop so we don't have to recalculate
	 */
	public void renderPortal(int textureId, int xxx, int yyy, int zzz, int blockOffset, int blockId) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		
		// Check to see where adjoining Portal spaces are, so we know which
		// faces to draw
		boolean drawWestEast = true;
		if (this.getAdjNorthBlockId(xxx, yyy, zzz, blockOffset) == blockId ||
				this.getAdjSouthBlockId(xxx, yyy, zzz, blockOffset) == blockId)
		{
			drawWestEast = false;
		}

		if (drawWestEast)
		{
			this.renderVertical(textureId, x-0.3f, z-0.5f, x-0.3f, z+0.5f, y-0.5f, 1.0f);
			this.renderVertical(textureId, x+0.3f, z-0.5f, x+0.3f, z+0.5f, y-0.5f, 1.0f);
		}
		else
		{
			this.renderVertical(textureId, x-0.5f, z-0.3f, x+0.5f, z-0.3f, y-0.5f, 1.0f);
			this.renderVertical(textureId, x-0.5f, z+0.3f, x+0.5f, z+0.3f, y-0.5f, 1.0f);
		}
	}
	
	/**
	 * This is a bizarre little method, and should probably be both renamed and refactored
	 * into some functions that make more sense.  It's used in a few places to determine
	 * which faces of a block we're supposed to actually render.  "transparency" is whether
	 * or not we're currently rendering transparent objects.
	 */
	public boolean checkSolid(short block, boolean transparency) {
		if(block <= 0) {
			return true;
		}
		if (blockArray[block] == null)
		{
			return transparency;
		}
		return blockArray[block].isSolid() == transparency;
	}

	/**
	 * This method, on the other hand, is a bit more clear.  We'll return true if the
	 * block ID is solid.
	 */
	public boolean isSolid(short block)
	{
		if(block <= 0) {
			return false;
		}
		if (blockArray[block] == null)
		{
			return false;
		}
		return blockArray[block].isSolid();
	}
	
	/**
	 * Renders the body of a piston.  If the piston is retracted, we'll also make a call out
	 * to renderPistonHead to draw the actual head.  Note that we do need to have the block
	 * type passed in, so that we can tell renderPistonHead what texture to draw.
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 * @param blockType The actual block type; needed for the piston head
	 */
	public void renderPistonBody(int textureId, int xxx, int yyy, int zzz, short blockType) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		byte data = getData(xxx, yyy, zzz);
		boolean extended = ((data & 0x8) == 0x8);
		int direction = (data & 0x7);

		float tex_x = precalcSpriteSheetToTextureX[textureId];
		float tex_y = precalcSpriteSheetToTextureY[textureId]+TEX128;
		float TEX_PISTON = TEX128*3f;

		// Use GL to rotate these properly
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);

		// This routine draws the piston facing west, which is direction value 3
		if (direction == 1)
		{
			// Up
			GL11.glRotatef(-90f, 1f, 0f, 0f);
		}
		else if (direction == 2)
		{
			// East
			GL11.glRotatef(180f, 0f, 1f, 0f);
		}
		else if (direction == 4)
		{
			// North
			GL11.glRotatef(-90f, 0f, 1f, 0f);
		}
		else if (direction == 5)
		{
			// South
			GL11.glRotatef(90f, 0f, 1f, 0f);
		}

		// First the main body bit
		renderNonstandardHorizontal(tex_x, tex_y, TEX16, TEX_PISTON, -.49f, .25f, .49f, -.49f, .49f);
		renderNonstandardHorizontal(tex_x, tex_y, TEX16, TEX_PISTON, -.49f, .25f, .49f, -.49f, -.49f);
		renderNonstandardVertical(tex_x, tex_y, TEX16, TEX_PISTON, -.49f, .49f, .25f, -.49f, -.49f, -.49f);
		renderNonstandardVertical(tex_x, tex_y, TEX16, TEX_PISTON, .49f, .49f, .25f, .49f, -.49f, -.49f);
		renderVertical(textureId+1, -.49f, -.49f, .49f, -.49f, -.49f, .98f);

		// If we're extended, draw our faceplate; if not, draw the retracted face
		if (extended)
		{
			renderVertical(textureId+2, -.49f, .25f, .49f, .25f, -.49f, .98f);

			// Pop the matrix after
			GL11.glPopMatrix();
		}
		else
		{
			// Pop the matrix before
			GL11.glPopMatrix();

			renderPistonHead(textureId-1, xxx, yyy, zzz, true, (blockType == 29));
		}

	}

	/**
	 * Renders the head of a piston.  This can get called from either the main loop, or from
	 * renderPistonBody.  If called from renderPistonBody, "attached" should be set to true,
	 * so we know not to draw the back face and post.  The override_sticky boolean should be
	 * used (when attached is true) to specify whether to use the sticky-piston texture or
	 * not, since the data value we read will be the Body's data value, which does not
	 * indicate stickiness.  Fortunately, the remaining three bits (the direction) matches
	 * up between head and body, so we should be okay just reading the body's data for
	 * direction information.
	 *
	 * The textureId passed in should be the ID of the non-sticky texture; some logic here
	 * depends on the layout of the texture information in terrain.png
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 * @param attached Are we attached to the piston body?
	 * @param override_sticky If attached, are we a sticky piston or a regular piston?
	 */
	public void renderPistonHead(int textureId, int xxx, int yyy, int zzz, boolean attached, boolean override_sticky) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;
		byte data = getData(xxx, yyy, zzz);
		boolean sticky = ((data & 0x8) == 0x8);
		int direction = (data & 0x7);

		float side_tex_x = precalcSpriteSheetToTextureX[textureId+1];
		float side_tex_y = precalcSpriteSheetToTextureY[textureId+1];

		// Matrix stuff
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);

		// This routine draws the piston facing west, which is direction value 3
		if (direction == 1)
		{
			// Up
			GL11.glRotatef(-90f, 1f, 0f, 0f);
		}
		else if (direction == 2)
		{
			// East
			GL11.glRotatef(180f, 0f, 1f, 0f);
		}
		else if (direction == 4)
		{
			// North
			GL11.glRotatef(-90f, 0f, 1f, 0f);
		}
		else if (direction == 5)
		{
			// South
			GL11.glRotatef(90f, 0f, 1f, 0f);
		}

		// Outside edges
		renderNonstandardHorizontalTexRotate(side_tex_x, side_tex_y, TEX16, TEX128, -.49f, .25f, .49f, .49f, .49f);
		renderNonstandardHorizontalTexRotate(side_tex_x, side_tex_y, TEX16, TEX128, -.49f, .25f, .49f, .49f, -.49f);
		renderNonstandardVerticalTexRotate(side_tex_x, side_tex_y, TEX16, TEX128, -.49f, .49f, .25f, -.49f, -.49f, .49f);
		renderNonstandardVerticalTexRotate(side_tex_x, side_tex_y, TEX16, TEX128, .49f, .49f, .25f, .49f, -.49f, .49f);

		// Back face and post, if we're not attached
		if (!attached)
		{
			// Back face first
			renderVertical(textureId, -.49f, .25f, .49f, .25f, -.49f, .98f);

			// Now the post
			renderNonstandardHorizontal(side_tex_x, side_tex_y, TEX16, TEX128, -.125f, .25f, .125f, -.75f, .125f);
			renderNonstandardHorizontal(side_tex_x, side_tex_y, TEX16, TEX128, -.125f, .25f, .125f, -.75f, -.125f);
			renderNonstandardVertical(side_tex_x, side_tex_y, TEX16, TEX128, -.125f, .125f, .25f, -.125f, -.125f, -.75f);
			renderNonstandardVertical(side_tex_x, side_tex_y, TEX16, TEX128, .125f, .125f, .25f, .125f, -.125f, -.75f);
		}

		// Front face
		if (attached)
		{
			if (override_sticky)
			{
				textureId -= 1;
			}
		}
		else
		{
			if (sticky)
			{
				textureId -= 1;
			}
		}
		renderVertical(textureId, -.49f, .49f, .49f, .49f, -.49f, .98f);

		// Pop the matrix
		GL11.glPopMatrix();
	}
	
	/**
	 * Renders a cake
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderCake(int textureId, int xxx, int yyy, int zzz) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;

		byte bites_eaten = getData(xxx, yyy, zzz);

		float top_tex_x = precalcSpriteSheetToTextureX[textureId]+TEX256;
		float top_tex_y = precalcSpriteSheetToTextureY[textureId]+TEX512;
		float bottom_tex_x = precalcSpriteSheetToTextureX[textureId+3]+TEX256;
		float bottom_tex_y = precalcSpriteSheetToTextureY[textureId+3]+TEX512;
		float edge_tex_x = precalcSpriteSheetToTextureX[textureId+1]+TEX256;
		float edge_tex_y = precalcSpriteSheetToTextureY[textureId+1]+TEX64;
		float cut_tex_x = precalcSpriteSheetToTextureX[textureId+2]+TEX256;
		float cut_tex_y = precalcSpriteSheetToTextureY[textureId+2]+TEX64;

		float far_tex_x, far_tex_y;
		if (bites_eaten == 0)
		{
			far_tex_x = edge_tex_x;
			far_tex_y = edge_tex_y;
		}
		else
		{
			far_tex_x = cut_tex_x;
			far_tex_y = cut_tex_y;
		}


		float tex_full_width = TEX256*14f;
		float tex_full_height = TEX512*14f;
		float tex_side_height = TEX64;
		float cake_height = .5f;
		float cake_full_width = .875f;
		float cake_full_width_h = .4375f;

		float actual_width = (6f-(float)bites_eaten)/6f;

		// Use GL to rotate these properly
		GL11.glPushMatrix();
		GL11.glTranslatef(x, y, z);

		// Note that cake will always be eaten from the North
		// Knowing that, draw the south face, first
		renderNonstandardVertical(edge_tex_x, edge_tex_y, tex_full_width, tex_side_height,
				cake_full_width_h, 0f, cake_full_width_h,
				cake_full_width_h, -.49f, -cake_full_width_h);

		// Draw the far edge next
		renderNonstandardVertical(far_tex_x, far_tex_y, tex_full_width, tex_side_height,
				cake_full_width_h-(actual_width*cake_full_width), 0f, cake_full_width_h,
				cake_full_width_h-(actual_width*cake_full_width), -.49f, -cake_full_width_h);

		// And now the sides
		renderNonstandardVertical(edge_tex_x, edge_tex_y, tex_full_width*actual_width, tex_side_height,
				cake_full_width_h, 0f, cake_full_width_h,
				cake_full_width_h-(actual_width*cake_full_width), -.49f, cake_full_width_h);
		renderNonstandardVertical(edge_tex_x, edge_tex_y, tex_full_width*actual_width, tex_side_height,
				cake_full_width_h, 0f, -cake_full_width_h,
				cake_full_width_h-(actual_width*cake_full_width), -.49f, -cake_full_width_h);

		// Now the bottom
		renderNonstandardHorizontal(bottom_tex_x, bottom_tex_y, tex_full_width, tex_full_height*actual_width,
				cake_full_width_h, cake_full_width_h,
				cake_full_width_h-(actual_width*cake_full_width), -cake_full_width_h,
				-.49f);

		// ... and the top
		renderNonstandardHorizontal(top_tex_x, top_tex_y, tex_full_width, tex_full_height*actual_width,
				cake_full_width_h, cake_full_width_h,
				cake_full_width_h-(actual_width*cake_full_width), -cake_full_width_h,
				0f);

		// Pop the matrix
		GL11.glPopMatrix();
	}
	
	/**
	 * Renders a sold pane-like object (glass pane, iron bars, etc)
	 * 
	 * @param textureId
	 * @param xxx
	 * @param yyy
	 * @param zzz
	 */
	public void renderSolidPane(int textureId, int xxx, int yyy, int zzz, int blockOffset, int blockId) {
		float x = xxx + this.x*16;
		float z = zzz + this.z*16;
		float y = yyy;

		boolean has_north = false;
		boolean has_south = false;
		boolean has_west = false;
		boolean has_east = false;

		float top_width = .0625f;
		int top_row_1 = 0;
		int top_row_2 = 15;
		if (blockId == BLOCK_IRON_BARS.id)
		{
			top_row_1 = 2;
			top_row_2 = 3;
		}

		short temp_id;
		temp_id = this.getAdjNorthBlockId(xxx, yyy, zzz, blockOffset);
		if (temp_id == blockId || this.isSolid(temp_id))
		{
			has_north = true;
		}
		temp_id = this.getAdjSouthBlockId(xxx, yyy, zzz, blockOffset);
		if (temp_id == blockId || this.isSolid(temp_id))
		{
			has_south = true;
		}
		temp_id = this.getAdjWestBlockId(xxx, yyy, zzz, blockOffset);
		if (temp_id == blockId || this.isSolid(temp_id))
		{
			has_west = true;
		}
		temp_id = this.getAdjEastBlockId(xxx, yyy, zzz, blockOffset);
		if (temp_id == blockId || this.isSolid(temp_id))
		{
			has_east = true;
		}

		if (!has_north && !has_south && !has_west && !has_east)
		{
			has_north = true;
			has_south = true;
			has_west = true;
			has_east = true;
		}

		// Now we should be able to actually draw stuff
		if (has_north && has_south)
		{
			this.renderVertical(textureId, x-.5f, z, x+.5f, z, y-.5f, 1f);
		}
		else
		{
			if (has_north)
			{
				this.renderVertical(textureId, x, z, x-.5f, z, y-.5f, 1f, 8, 16, 8, 0);
			}
			if (has_south)
			{
				this.renderVertical(textureId, x, z, x+.5f, z, y-.5f, 1f, 8, 16, 8, 0);
			}
		}
		if (has_north)
		{
			this.renderHorizontal(textureId, x-.5f, z+top_width, x-top_width, z, y+.5f, 1, 7, top_row_1, 0, false);
			this.renderHorizontal(textureId, x-.5f, z-top_width, x-top_width, z, y+.5f, 1, 7, top_row_2, 0, false);
		}
		if (has_south)
		{
			this.renderHorizontal(textureId, x+.5f, z+top_width, x+top_width, z, y+.5f, 1, 7, top_row_1, 0, false);
			this.renderHorizontal(textureId, x+.5f, z-top_width, x+top_width, z, y+.5f, 1, 7, top_row_2, 0, false);
		}

		if (has_west && has_east)
		{
			this.renderVertical(textureId, x, z-.5f, x, z+.5f, y-.5f, 1f);
		}
		else
		{
			if (has_west)
			{
				this.renderVertical(textureId, x, z, x, z+.5f, y-.5f, 1f, 8, 16, 8, 0);
			}
			if (has_east)
			{
				this.renderVertical(textureId, x, z, x, z-.5f, y-.5f, 1f, 8, 16, 8, 0);
			}
		}
		if (has_west)
		{
			this.renderHorizontal(textureId, x+top_width, z+.5f, x, z+top_width, y+.5f, 1, 7, top_row_1, 0, true);
			this.renderHorizontal(textureId, x-top_width, z+.5f, x, z+top_width, y+.5f, 1, 7, top_row_2, 0, true);
		}
		if (has_east)
		{
			this.renderHorizontal(textureId, x+top_width, z-.5f, x, z-top_width, y+.5f, 1, 7, top_row_1, 0, true);
			this.renderHorizontal(textureId, x-top_width, z-.5f, x, z-top_width, y+.5f, 1, 7, top_row_2, 0, true);
		}

		// Finally, the center top square.  Technically we shouldn't draw past the edge, but whatever.
		this.renderHorizontal(textureId, x+top_width, z+top_width, x-top_width, z, y+.5f, 1, 2, top_row_1, 7, false);
		this.renderHorizontal(textureId, x+top_width, z-top_width, x-top_width, z, y+.5f, 1, 2, top_row_2, 7, false);
	}
	
	/**
	 * Tests if the given source block has a torch nearby.  This is, I'm willing
	 * to bet, the least efficient way possible of doing this.  It turns out that
	 * despite that, it doesn't really have a noticeable impact on performance,
	 * which is why it remains in here, but perhaps one day I'll rewrite this
	 * stuff to be less stupid.  The one upside to doing it like this is that
	 * we're not using any extra memory storing data about which block should be
	 * highlighted...
	 * 
	 * @param sx
	 * @param sy
	 * @param sz
	 * @return
	 */
	public boolean hasAdjacentTorch(int sx, int sy, int sz)
	{
		int distance = 3;
		int x, y, z;
		int min_x = sx-distance;
		int max_x = sx+distance;
		int min_z = sz-distance;
		int max_z = sz+distance;
		int min_y = Math.max(0, sy-distance);
		int max_y = Math.min(127, sy+distance);
		Chunk otherChunk;
		int cx, cz;
		int tx, tz;
		for (x = min_x; x<=max_x; x++)
		{
			for (y = min_y; y<=max_y; y++)
			{
				for (z = min_z; z<=max_z; z++)
				{
					otherChunk = null;
					if (x < 0)
					{
						cx = this.x-1;
						tx = 16+x;
					}
					else if (x > 15)
					{
						cx = this.x+1;
						tx = x-16;
					}
					else
					{
						cx = this.x;
						tx = x;
					}

					if (z < 0)
					{
						cz = this.z-1;
						tz = 16+z;
					}
					else if (z > 15)
					{
						cz = this.z+1;
						tz = z-16;
					}
					else
					{
						cz = this.z;
						tz = z;
					}
					
					if (cx != this.x || cz != this.z)
					{
						otherChunk = level.getChunk(cx, cz);
						if (otherChunk == null)
						{
							continue;
						}
						else if (otherChunk.blockData.value[(tz*128)+(tx*128*16)+y] == BLOCK_TORCH.id)
						{
							return true;
						}
					}
					else
					{
						if (blockData.value[(z*128)+(x*128*16)+y] == BLOCK_TORCH.id)
						{
							return true;
						}
					}
				}
			}
		}
		return false;
	}
	
	/**
	 * Renders our chunk.  Most of these options should really be consolidated somehow; maybe just pass in
	 * a HashMap or something with the options.  Anyway, for now it'll remain the same.
	 * 
	 * @param transparency Are we rendering "transparent" objects this time?  (ie: any nonstandard, nonsolid block)
	 * @param render_bedrock Are we forcing bedrock to be rendered?
	 * @param render_water Are we forcing water to be rendered?
	 * @param highlight_explored Are we highlighting the area around torches?
	 * @param onlySelected Are we ONLY rendering ores that the user's selected?
	 * @param selectedMap ... if so, here's a HashMap to which ones to highlight.
	 */
	public void renderWorld(boolean transparency, boolean render_bedrock, boolean render_water, boolean highlight_explored,
			boolean onlySelected, boolean[] selectedMap) {
		float worldX = this.x*16;
		float worldZ = this.z*16;
		
		boolean draw = false;
		boolean above = true;
		boolean below = true;
		boolean left = true;
		boolean right = true;
		boolean near = true;
		boolean far = true;
		int tex_offset = 0;
		BlockType block;

		int north, south, west, east, top, bottom;
		
		for(int x=0;x<16;x++) {
			int xOff = (x * 128 * 16);
			for(int z=0;z<16;z++) {
				int zOff = (z * 128);
				int blockOffset = zOff + xOff-1;
				for(int y=0;y<128;y++) {
					blockOffset++;
					short t = blockData.value[blockOffset];
					
					if(t < 1) {
						continue;
					}

					block = blockArray[t];
					if (block == null)
					{
						block = BLOCK_UNKNOWN;
					}
					
					if (onlySelected)
					{
						draw = false;
						for(int i=0;i<selectedMap.length;i++) {
							if(selectedMap[i] && level.HIGHLIGHT_ORES[i] == t) {
								// TODO: should maybe check our boundaries for similar ores, like we do for regular blocks
								draw = true;
								above = false;
								below = false;
								left = false;
								right = false;
								near = false;
								far = false;
								break;
							}
						}
					}
					else
					{
						if(transparency && block.isSolid()) {
							continue;
						}
						if(!transparency && !block.isSolid()) {
							continue;
						}
						
						draw = false;
						above = true;
						below = true;
						left = true;
						right = true;
						near = true;
						far = true;
					}
					
					if (!render_water && block.type == BLOCK_TYPE.WATER)
					{
						continue;
					}
					
					int textureId = block.tex_idx;
					
					if(textureId == -1) {
						//System.out.println("Unknown block id: " + t);
						continue;
					}
					/*
					if(textureId == 253) {
						System.out.println("Unknown block id: " + t);
					}
					*/

					if (!onlySelected)
					{
						if (render_bedrock && t == BLOCK_BEDROCK.id)
						{
							// This block of code was more or less copied/modified directly from the "else" block
							// below - should see if there's a way we can abstract this instead.  Also, I suspect
							// that this is where we'd fix water rendering...
							
							// check above
							if(y<127 && blockData.value[blockOffset+1] != BLOCK_BEDROCK.id) {
								draw = true;
								above = false;
							}
							
							// check below
							if(y>0 && blockData.value[blockOffset-1] != BLOCK_BEDROCK.id) {
								draw = true;
								below = false;
							}
							
							// check left;
							if (this.getAdjNorthBlockId(x, y, z, blockOffset) != BLOCK_BEDROCK.id) {
								draw = true;
								left = false;
							}
						
							// check right
							if (this.getAdjSouthBlockId(x, y, z, blockOffset) != BLOCK_BEDROCK.id) {
								draw = true;
								right = false;
							}
							
							// check near
							if (this.getAdjEastBlockId(x, y, z, blockOffset) != BLOCK_BEDROCK.id) {
								draw = true;
								near = false;
							}
							
							// check far
							if (this.getAdjWestBlockId(x, y, z, blockOffset) != BLOCK_BEDROCK.id) {
								draw = true;
								far = false;
							}
						}
						else
						{
							// check above
							if(y<127 && checkSolid(blockData.value[blockOffset+1], transparency)) {
								draw = true;
								above = false;
							}
							
							// check below
							if(y>0 && checkSolid(blockData.value[blockOffset-1], transparency)) {
								draw = true;
								below = false;
							}
							
							// check left;
							if (checkSolid(this.getAdjNorthBlockId(x, y, z, blockOffset), transparency)) {
								draw = true;
								left = false;
							}
						
							// check right
							if (checkSolid(this.getAdjSouthBlockId(x, y, z, blockOffset), transparency)) {
								draw = true;
								right = false;
							}
							
							// check near
							if (checkSolid(this.getAdjEastBlockId(x, y, z, blockOffset), transparency)) {
								draw = true;
								near = false;
							}
							
							// check far
							if (checkSolid(this.getAdjWestBlockId(x, y, z, blockOffset), transparency)) {
								draw = true;
								far = false;
							}
						}
					}
					
					boolean adj_torch = false;
					if (draw)
					{
						// Check to see if this block type has a texture ID which changes depending
						// on the block's data value
						if (block.texture_data_map != null)
						{
							byte data = getData(x, y, z);

							if (t == BLOCK_SAPLING.id)
							{
								// Special-case here for Sapling data, since we can't trust the upper two bits
								data &= 0x3;
							}
							else
							{
								// ... otherwise, just make sure we're dealing with the bottom four
								data &= 0xF;
							}

							// Now try to get the new texture
							try
							{
								textureId = block.texture_data_map.get(data);
							}
							catch (NullPointerException e)
							{
								// Just report and continue
								System.out.println("Unknown data value for block " + block.idStr + ": " + data);
							}
						}

						// If we're highlighting explored regions and there's an adjacent
						// torch, flip over to the "highlighted" textures
						if (highlight_explored)
						{
							adj_torch = hasAdjacentTorch(x,y,z);
							if (adj_torch)
							{
								textureId += 256;
								tex_offset = 256;
							}
							else
							{
								tex_offset = 0;
							}
						}
						else
						{
							tex_offset = 0;
						}

						// Now process the actual drawing
						switch(block.type)
						{
							case TORCH:
								renderTorch(textureId,x,y,z);
								break;
							case DECORATION_CROSS:
								renderCrossDecoration(textureId,x,y,z);
								break;
							case CROPS:
								renderCrops(textureId,x,y,z);
								break;
							case LADDER:
								renderLadder(textureId,x,y,z);
								break;
							case FLOOR:
								renderFloor(textureId,x,y,z);
								break;
							case MINECART_TRACKS:
								renderMinecartTracks(textureId,x,y,z);
								break;
							case SIMPLE_RAIL:
								renderSimpleRail(textureId,x,y,z);
								break;
							case PRESSURE_PLATE:
								renderPlate(textureId,x,y,z);
								break;
							case DOOR:
								renderDoor(textureId,x,y,z);
								break;
							case STAIRS:
								renderStairs(textureId,x,y,z);
								break;
							case SIGNPOST:
								renderSignpost(textureId,x,y,z);
								break;
							case WALLSIGN:
								renderWallSign(textureId,x,y,z);
								break;
							case FENCE:
								renderFence(textureId,x,y,z,blockOffset);
								break;
							case FENCE_GATE:
								renderFenceGate(textureId,x,y,z,blockOffset);
								break;
							case LEVER:
								renderLever(textureId,x,y,z);
								break;
							case BUTTON:
								renderButton(textureId,x,y,z);
								break;
							case PORTAL:
								renderPortal(textureId,x,y,z,blockOffset,t);
								break;
							case THINSLICE:
								renderThinslice(textureId,x,y,z);
								break;
							case BED:
								renderBed(textureId,x,y,z);
								break;
							case TRAPDOOR:
								renderTrapdoor(textureId,x,y,z);
								break;
							case PISTON_BODY:
								renderPistonBody(textureId,x,y,z,t);
								break;
							case PISTON_HEAD:
								renderPistonHead(textureId,x,y,z,false,false);
								break;
							case CAKE:
								renderCake(textureId,x,y,z);
								break;
							case VINE:
								renderVine(textureId,x,y,z,blockOffset);
								break;
							case SOLID_PANE:
								renderSolidPane(textureId,x,y,z,blockOffset,t);
								break;
							case HALFHEIGHT:
								if(draw) {
									if(!near) this.renderWestEast(textureId, worldX+x, y, worldZ+z, 0f, .495f);
									if(!far) this.renderWestEast(textureId, worldX+x, y, worldZ+z+1, 0f, .495f);
									
									if(!below) this.renderTopDown(textureId, worldX+x, y, worldZ+z);
									this.renderTopDown(textureId, worldX+x, y+0.5f, worldZ+z);	
									
									if(!left) this.renderNorthSouth(textureId, worldX+x, y, worldZ+z, 0f, .495f);
									if(!right) this.renderNorthSouth(textureId, worldX+x+1, y, worldZ+z, 0f, .495f);
								}
								break;
							default:
								north = textureId;
								south = textureId;
								west = textureId;
								east = textureId;
								top = textureId;
								bottom = textureId;
								if (block.type == BLOCK_TYPE.HUGE_MUSHROOM)
								{
									byte data = getData(x, y, z);
									switch (data)
									{
										case 0:
											north = TEX_HUGE_MUSHROOM_PORES;
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											top = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
									    case 1:
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 2:
											north = TEX_HUGE_MUSHROOM_PORES;
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 3:
											north = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 4:
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 5:
											north = TEX_HUGE_MUSHROOM_PORES;
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 6:
											north = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 7:
											south = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 8:
											north = TEX_HUGE_MUSHROOM_PORES;
											south = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 9:
											north = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										case 10:
											north = TEX_HUGE_MUSHROOM_STEM;
											south = TEX_HUGE_MUSHROOM_STEM;
											west = TEX_HUGE_MUSHROOM_STEM;
											east = TEX_HUGE_MUSHROOM_STEM;
											top = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
										default:
											north = TEX_HUGE_MUSHROOM_PORES;
											south = TEX_HUGE_MUSHROOM_PORES;
											west = TEX_HUGE_MUSHROOM_PORES;
											east = TEX_HUGE_MUSHROOM_PORES;
											top = TEX_HUGE_MUSHROOM_PORES;
											bottom = TEX_HUGE_MUSHROOM_PORES;
											break;
									}
								}
								if (block.texture_dir_map != null)
								{
									byte data = getData(x, y, z);
									BlockType.DIRECTION_ABS dir;
									if (block.texture_dir_data_map != null && block.texture_dir_data_map.containsKey(data))
									{
										dir = block.texture_dir_data_map.get(data);
									}
									else
									{
										dir = BlockType.DIRECTION_ABS.NORTH;
									}

									switch (dir)
									{
										case NORTH:
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.FORWARD))
											{
												north = block.texture_dir_map.get(BlockType.DIRECTION_REL.FORWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.BACKWARD))
											{
												south = block.texture_dir_map.get(BlockType.DIRECTION_REL.BACKWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.SIDES))
											{
												west = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
												east = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
											}
											break;
										case SOUTH:
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.BACKWARD))
											{
												north = block.texture_dir_map.get(BlockType.DIRECTION_REL.BACKWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.FORWARD))
											{
												south = block.texture_dir_map.get(BlockType.DIRECTION_REL.FORWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.SIDES))
											{
												west = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
												east = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
											}
											break;
										case WEST:
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.SIDES))
											{
												north = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
												south = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.FORWARD))
											{
												west = block.texture_dir_map.get(BlockType.DIRECTION_REL.FORWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.BACKWARD))
											{
												east = block.texture_dir_map.get(BlockType.DIRECTION_REL.BACKWARD) + tex_offset;
											}
											break;
										case EAST:
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.SIDES))
											{
												north = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
												south = block.texture_dir_map.get(BlockType.DIRECTION_REL.SIDES) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.BACKWARD))
											{
												west = block.texture_dir_map.get(BlockType.DIRECTION_REL.BACKWARD) + tex_offset;
											}
											if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.FORWARD))
											{
												east = block.texture_dir_map.get(BlockType.DIRECTION_REL.FORWARD) + tex_offset;
											}
											break;
									}

									// Top/Bottom doesn't depend on orientation, at least for anything currently in Minecraft.
									// If Minecraft starts adding blocks that can be oriented Up or Down, we'll have to move
									// this back into the case statement above
									if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.TOP))
									{
										top = block.texture_dir_map.get(BlockType.DIRECTION_REL.TOP) + tex_offset;
									}
									if (block.texture_dir_map.containsKey(BlockType.DIRECTION_REL.BOTTOM))
									{
										bottom = block.texture_dir_map.get(BlockType.DIRECTION_REL.BOTTOM) + tex_offset;
									}
								}

								if(!near) this.renderWestEast(east, worldX+x, y, worldZ+z);
								if(!far) this.renderWestEast(west, worldX+x, y, worldZ+z+1);
								
								if(!below) this.renderTopDown(bottom, worldX+x, y, worldZ+z);
								if(!above) this.renderTopDown(top, worldX+x, y+1, worldZ+z);	
								
								if(!left) this.renderNorthSouth(north, worldX+x, y, worldZ+z);
								if(!right) this.renderNorthSouth(south, worldX+x+1, y, worldZ+z);
						}					
					}
				}
			}
		}
	}
	
	/**
	 * Renders paintings into our world.  Paintings are stored as Entities, not
	 * block-level data, so they have to be handled differently than everything else.
	 */
	public void renderPaintings()
	{
		PaintingInfo info;
		float start_x;
		float start_y;
		float start_z;
		float back_x;
		float back_z;
		float dX;
		float dZ;
		for (PaintingEntity painting : this.paintings)
		{
			info = MinecraftConstants.paintings.get(painting.name.toLowerCase());
			if (info == null)
			{
				System.out.println("Unknown painting name: " + painting.name);
				continue;
			}
			
			start_y = painting.tile_y + 0.5f + info.centery;
			switch (painting.dir)
			{
				case 0x0:
					// East
					start_x = painting.tile_x + 0.5f + info.centerx;
					start_z = painting.tile_z - 0.5f - TEX16;
					back_x = start_x;
					back_z = start_z + TEX32;
					dX = -1;
					dZ = 0;
					break;
				case 0x1:
					// North
					start_x = painting.tile_x - 0.5f - TEX16;
					start_z = painting.tile_z - 0.5f - info.centerx;
					back_x = start_x + TEX32;
					back_z = start_z;
					dX = 0;
					dZ = 1;
					break;
				case 0x2:
					// West
					start_x = painting.tile_x - 0.5f - info.centerx;
					start_z = painting.tile_z + 0.5f + TEX16;
					back_x = start_x;
					back_z = start_z - TEX32;
					dX = 1;
					dZ = 0;
					break;
				case 0x3:
				default:
					// South
					start_x = painting.tile_x + 0.5f + TEX16;
					start_z = painting.tile_z + 0.5f + info.centerx;
					back_x = start_x - TEX32;
					back_z = start_z;
					dX = 0;
					dZ = -1;
					break;
			}
			
			// Draw the painting face
			renderNonstandardVertical(info.offsetx, info.offsety, info.sizex_tex, info.sizey_tex,
					start_x, start_y, start_z,
					start_x + (dX*info.sizex), start_y-info.sizey, start_z + (dZ*info.sizex));
			
			PaintingInfo backinfo = MinecraftConstants.paintingback;

			// Back
			renderNonstandardVertical(backinfo.offsetx, backinfo.offsety, info.sizex_tex, info.sizey_tex,
					back_x, start_y, back_z,
					back_x + (dX*info.sizex), start_y-info.sizey, back_z + (dZ*info.sizex));
 
			// Sides
			renderNonstandardVertical(backinfo.offsetx, backinfo.offsety, info.sizex_tex, info.sizey_tex,
					start_x, start_y, start_z,
					back_x, start_y-info.sizey, back_z);
			renderNonstandardVertical(backinfo.offsetx, backinfo.offsety, info.sizex_tex, info.sizey_tex,
					start_x + (dX*info.sizex), start_y, start_z + (dZ*info.sizex),
					back_x + (dX*info.sizex), start_y-info.sizey, back_z + (dZ*info.sizex));
			
			// Top/Bottom
			renderNonstandardHorizontal(backinfo.offsetx, backinfo.offsety, info.sizex_tex, info.sizey_tex,
					start_x, start_z,
					back_x + (dX*info.sizex), back_z + (dZ*info.sizex),
					start_y-info.sizey
					);
			renderNonstandardHorizontal(backinfo.offsetx, backinfo.offsety, info.sizex_tex, info.sizey_tex,
					start_x, start_z,
					back_x + (dX*info.sizex), back_z + (dZ*info.sizex),
					start_y
					);
		}
	}
	
	public void renderSolid(boolean render_bedrock, boolean render_water, boolean highlight_explored) {
		if(isDirty) {
				GL11.glNewList(this.displayListNum, GL11.GL_COMPILE);
				renderWorld(false, render_bedrock, false, highlight_explored, false, null);
				GL11.glEndList();
				GL11.glNewList(this.transparentListNum, GL11.GL_COMPILE);
				//GL11.glDepthMask(false);
				renderWorld(true, false, render_water, highlight_explored, false, null);
				//GL11.glDepthMask(true);
				GL11.glEndList();
				this.isDirty = false;
		}
		GL11.glCallList(this.displayListNum);
	}
	
	public void renderTransparency() {
		GL11.glCallList(this.transparentListNum);
	}
	
	public void renderSelected(boolean[] selectedMap) {
		if(isSelectedDirty) {
			GL11.glNewList(this.selectedDisplayListNum, GL11.GL_COMPILE);
			renderWorld(false, false, false, false, true, selectedMap);
			GL11.glEndList();
			this.isSelectedDirty = false;
		}
		GL11.glCallList(this.selectedDisplayListNum);
	}
}
