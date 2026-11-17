package dev.anilbeesetti.nextplayer.core.database

import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
@Module
class DaoModule {

    @Factory
    fun provideMediumStateDao(db: MediaDatabase) = db.mediumStateDao()

    @Factory
    fun provideMediaFileIndexDao(db: MediaDatabase) = db.mediaFileIndexDao()

    @Factory
    fun provideAudioFileIndexDao(db: MediaDatabase) = db.audioFileIndexDao()

    @Factory
    fun provideImageFileIndexDao(db: MediaDatabase) = db.imageFileIndexDao()

    @Factory
    fun provideHiddenVideoDao(db: MediaDatabase) = db.hiddenVideoDao()

    @Factory
    fun provideNetworkConnectionDao(db: MediaDatabase) = db.networkConnectionDao()

    @Factory
    fun providePlaylistDao(db: MediaDatabase) = db.playlistDao()
}
