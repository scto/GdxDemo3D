/*******************************************************************************
 * Copyright 2015 See AUTHORS file.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/


package com.mygdx.game.objects;

import java.util.EnumMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ai.fsm.DefaultStateMachine;
import com.badlogic.gdx.ai.fsm.State;
import com.badlogic.gdx.ai.fsm.StateMachine;
import com.badlogic.gdx.ai.msg.MessageManager;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.utils.Bits;
import com.mygdx.game.settings.GameSettings;
import com.mygdx.game.steerers.FollowPathSteerer;
import com.mygdx.game.utilities.AnimationListener;
import com.mygdx.game.utilities.Constants;
import com.mygdx.game.utilities.Sounds;

/**
 * A human character whose brain is modeled through a finite state machine.
 *
 * @author jsjolund
 */
public class HumanCharacter extends Ragdoll {

	public enum HumanState implements State<HumanCharacter> {
		IDLE_STAND(true) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|idle_stand", -1, 1, animationListener(entity), 0.2f);
			}
		},
		IDLE_CROUCH(true) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|idle_crouch", -1, 1, animationListener(entity), 0.2f);
			}
		},
		IDLE_CRAWL(true) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|idle_crouch", -1, 1, animationListener(entity), 0.2f);
			}
		},
		MOVE_RUN(HumanState.IDLE_STAND, 0.2f) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|move_run", -1, 1, animationListener(entity), 0.1f);

				prepareToMove(entity, HumanSteerSettings.runMultiplier);
			}
		},
		MOVE_WALK(HumanState.IDLE_STAND, 0.4f) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|move_walk", -1, 1, animationListener(entity), 0.1f);

				prepareToMove(entity, 1);
			}
		},
		MOVE_CROUCH(HumanState.IDLE_CROUCH, 0.5f) {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|move_crouch", -1, 1, animationListener(entity), 0.15f);

				prepareToMove(entity, HumanSteerSettings.crouchMultiplier);
			}
		},
		MOVE_CRAWL() {},  // Currently not used
		THROW() {
			@Override
			public void enter(HumanCharacter entity) {
				entity.animations.animate("armature|action_throw", 1, 1, animationListener(entity), 0.1f);

				// If the entity owns a dog send it a delayed message to emulate reaction time
				if (entity.dog != null) {
					MessageManager.getInstance().dispatchMessage(MathUtils.randomTriangular(.3f, 1.2f, .6f), null, entity.dog,
						Constants.MSG_DOG_STICK_THROWN);
				}
			}

			@Override
			public void update(HumanCharacter entity) {
				// Keep on updating throw animation
				updateAnimation(entity);

				AnimationListener animationListener = (AnimationListener)entity.animations.current.listener;
				if (animationListener.isAnimationCompleted()) {
					// Transition to the appropriate idle state depending on the previous state
					HumanState previousState = entity.stateMachine.getPreviousState();
					HumanState nextState = HumanState.IDLE_STAND;
					if (previousState != null) {
						if (previousState.isMovementState()) {
							nextState = previousState.idleState;
						}
						else if (previousState.isIdleState()) {
							nextState = previousState;
						}
					}
					entity.stateMachine.changeState(nextState);
				}
			}
		},
		WHISTLE() {
			@Override
			public void enter(HumanCharacter entity) {
				// Stop steering and let friction and gravity arrest the entity
				entity.stopSteering(false);

				HumanState prevState = entity.stateMachine.getPreviousState();
				if (prevState != null && prevState.isMovementState()) {
					// Save animation speed multiplier
					entity.animationSpeedMultiplier = prevState.animationMultiplier;
				}
				MessageManager.getInstance().dispatchMessage(Constants.MSG_GUI_CLEAR_DOG_BUTTON, entity);
			}

			@Override
			public void update(HumanCharacter entity) {
				if (entity.isMoving()) {
					// Keep on updating movement animation
					updateAnimation(entity);
				}
				else {
					Sounds.whistle.play();
					// If the entity owns a dog send it a delayed message to emulate reaction time
					if (entity.dog != null) {
						MessageManager.getInstance().dispatchMessage(MathUtils.randomTriangular(.8f, 2f, 1.2f), null, entity.dog,
							Constants.MSG_DOG_LETS_PLAY);
					}
					// Transition to the appropriate idle state depending on the previous state
					HumanState previousState = entity.stateMachine.getPreviousState();
					HumanState nextState = HumanState.IDLE_STAND;
					if (previousState != null) {
						if (previousState.isMovementState()) {
							nextState = previousState.idleState;
						}
						else if (previousState.isIdleState()) {
							nextState = previousState;
						}
					}
					entity.stateMachine.changeState(nextState);
				}
			}

			@Override
			public void exit(HumanCharacter entity) {
				// Reset entity's animation speed multiplier
				entity.animationSpeedMultiplier = -1;
			}
		},
		DEAD() {
			@Override
			public void enter(HumanCharacter entity) {
				// Turn off animation
				entity.animations.setAnimation("armature|idle_stand", -1);
				entity.animations.paused = true;

				// Stop steering and let friction and gravity arrest the entity
				entity.stopSteering(false);

				// Set ragdoll control
				entity.setRagdollControl(true);

				// Dog owners inform the dog of the death and clear dog button
				if (entity.dog != null) {
					MessageManager.getInstance().dispatchMessage(MathUtils.randomTriangular(.8f, 2f, 1.2f), null, entity.dog, Constants.MSG_DOG_HUMAN_IS_DEAD);
					MessageManager.getInstance().dispatchMessage(Constants.MSG_GUI_CLEAR_DOG_BUTTON, entity);
				}
			}

//			@Override
//			public void update(HumanCharacter entity) {
//			}

			@Override
			public void exit(HumanCharacter entity) {
				entity.animations.paused = false;
				entity.setRagdollControl(false);

				// Dog owners inform the dog of the resurrection and enable whistle button
				if (entity.dog != null) {
					MessageManager.getInstance().dispatchMessage(MathUtils.randomTriangular(.8f, 1.5f), null, entity.dog, Constants.MSG_DOG_HUMAN_IS_RESURRECTED);
					MessageManager.getInstance().dispatchMessage(Constants.MSG_GUI_SET_DOG_BUTTON_TO_WHISTLE, entity);
				}
			}
		};

		public final HumanState idleState;
		protected final float animationMultiplier;

		private HumanState() {
			this(false);
		}

		private HumanState(boolean idle) {
			this(null, idle ? -1 : 0);
		}

		private HumanState(HumanState idleState, float animationMultiplier) {
			this.idleState = idleState;
			this.animationMultiplier = animationMultiplier;
		}

		public boolean isMovementState() {
			return idleState != null;
		}

		public boolean isIdleState() {
			return idleState == null && animationMultiplier < 0;
		}

		protected AnimationListener animationListener(HumanCharacter entity) {
			AnimationListener animationListener = entity.stateAnimationListeners.get(this);
			if (animationListener != null)
				animationListener.setAnimationCompleted(false);
			return animationListener;
		}

		protected void prepareToMove(HumanCharacter entity, float steeringMultiplier) {
			entity.moveState = this;

			// Apply the multiplier to steering limits
			entity.setMaxLinearSpeed(HumanSteerSettings.maxLinearSpeed * steeringMultiplier);
			entity.setMaxLinearAcceleration(HumanSteerSettings.maxLinearAcceleration * steeringMultiplier);
			entity.setMaxAngularSpeed(HumanSteerSettings.maxAngularSpeed * steeringMultiplier);
			entity.setMaxAngularAcceleration(HumanSteerSettings.maxAngularAcceleration * steeringMultiplier);

			entity.followPathSteerer.followPathSB.setDecelerationRadius(HumanSteerSettings.decelerationRadius * steeringMultiplier);

			// If the entity owns a dog tell him you don't want to play and re-enable whistle
			if (entity.dog != null) {
				MessageManager.getInstance().dispatchMessage(MathUtils.randomTriangular(.8f, 2f, 1.2f), null, entity.dog, Constants.MSG_DOG_LETS_STOP_PLAYING);
				MessageManager.getInstance().dispatchMessage(Constants.MSG_GUI_SET_DOG_BUTTON_TO_WHISTLE, entity);
			}
		}

		@Override
		public void enter(HumanCharacter entity) {
		}

		@Override
		public void update(HumanCharacter entity) {
			if (entity.isSteering()) {
				if (!this.isMovementState()) {
					entity.stateMachine.changeState(entity.moveState);
					return;
				}
			} else {
				if (this.isMovementState()) {
					entity.stateMachine.changeState(this.idleState);
					return;
				}
			}

			updateAnimation(entity);
		}

		@Override
		public void exit(HumanCharacter entity) {
		}

		@Override
		public boolean onMessage(HumanCharacter entity, Telegram telegram) {
			return false;
		}

		protected void updateAnimation(HumanCharacter entity) {
			float deltaTime = Gdx.graphics.getDeltaTime();
			// Use entity's animation speed multiplier, if any
			float multiplier = entity.animationSpeedMultiplier > 0 ? entity.animationSpeedMultiplier : animationMultiplier;
			if (multiplier > 0) {
				deltaTime *= entity.getLinearVelocity().len() * multiplier;
			}
			entity.animations.update(deltaTime * GameSettings.GAME_SPEED);
		}
	}

	public static class HumanSteerSettings implements SteerSettings {
		public static float maxLinearAcceleration = 50f;
		public static float maxLinearSpeed = 2f;
		public static float maxAngularAcceleration = 100f;
		public static float maxAngularSpeed = 15f;
		public static float idleFriction = 0.9f;
		public static float zeroLinearSpeedThreshold = 0.001f;
		public static float runMultiplier = 2f;
		public static float crouchMultiplier = 0.5f;
		public static float timeToTarget = 0.1f;
		public static float arrivalTolerance = 0.1f;
		public static float decelerationRadius = 0.5f;
		public static float predictionTime = 0f;
		public static float pathOffset = 1f;

		@Override
		public float getTimeToTarget() {
			return timeToTarget;
		}

		@Override
		public float getArrivalTolerance() {
			return arrivalTolerance;
		}

		@Override
		public float getDecelerationRadius() {
			return decelerationRadius;
		}

		@Override
		public float getPredictionTime() {
			return predictionTime;
		}

		@Override
		public float getPathOffset() {
			return pathOffset;
		}

		@Override
		public float getZeroLinearSpeedThreshold() {
			return zeroLinearSpeedThreshold;
		}

		@Override
		public float getIdleFriction() {
			return idleFriction;
		}
	}

	public final StateMachine<HumanCharacter, HumanState> stateMachine;
	public final AnimationController animations;
	public final EnumMap<HumanState, AnimationListener> stateAnimationListeners;
	public HumanState moveState = HumanState.MOVE_WALK;
	public DogCharacter dog;
	private float animationSpeedMultiplier = -1;
	public boolean selected = false;

	final FollowPathSteerer followPathSteerer;

	public HumanCharacter(Model model,
						  String name,
						  Vector3 location,
						  Vector3 rotation,
						  Vector3 scale,
						  btCollisionShape shape,
						  float mass,
						  short belongsToFlag,
						  short collidesWithFlag,
						  boolean callback,
						  boolean noDeactivate,
						  String ragdollJson,
						  String armatureNodeId) {

		super(model, name, location, rotation, scale,
				shape, mass, belongsToFlag, collidesWithFlag,
				callback, noDeactivate, ragdollJson, armatureNodeId,
				new HumanSteerSettings());

		// Create path follower
		followPathSteerer = new FollowPathSteerer(this);

		// Create the animation controllers
		animations = new AnimationController(modelInstance);
		// Create animation listeners for states that need one
		stateAnimationListeners = new EnumMap<HumanState, AnimationListener>(HumanState.class);
		stateAnimationListeners.put(HumanState.THROW, new AnimationListener());

		// Create the state machine
		stateMachine = new DefaultStateMachine<HumanCharacter, HumanState>(this);
		// Set the steering variables associated with default move state (walking)
		stateMachine.changeState(moveState);
		// Then make the character idle
		stateMachine.changeState(moveState.idleState);
	}

	public void assignDog(DogCharacter dog) {
		this.dog = dog;
		dog.human = this;
	}

	public boolean isDead() {
		return stateMachine.getCurrentState() == HumanState.DEAD;
	}

	public boolean wantToPlay() {
		return stateMachine.getCurrentState() == HumanState.WHISTLE;
	}

	@Override
	public void update(float deltaTime) {
		super.update(deltaTime);
		stateMachine.update();
	}

	@Override
	public void handleMovementRequest(Ray ray, Bits visibleLayers) {
		// A man only moves if is idle or already moving
		// For instance, the movement request will be ignored if the man is throwing the stick
		HumanState state = stateMachine.getCurrentState();
		if (state.isIdleState() || state.isMovementState()) {
			followPathSteerer.calculateNewPath(ray, visibleLayers);
		}
	}

	public void handleStateCommand(HumanState newState) {
		stateMachine.changeState(newState);
	}

	public HumanState getCurrentMoveState() {
		return moveState;
	}

	public HumanState getCurrentIdleState() {
		return moveState.idleState;
	}

	public Vector3 getRightHandWorldPosition(Vector3 out) {
		return getBoneMidpointWorldPosition("right_hand", out);
	}

	public Vector3 getLeftHandWorldPosition(Vector3 out) {
		return getBoneMidpointWorldPosition("left_hand", out);
	}

}
