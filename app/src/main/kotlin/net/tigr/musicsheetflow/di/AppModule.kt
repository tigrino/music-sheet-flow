package net.tigr.musicsheetflow.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.tigr.musicsheetflow.audio.NativeAudioEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNativeAudioEngine(): NativeAudioEngine {
        return NativeAudioEngine()
    }
}
